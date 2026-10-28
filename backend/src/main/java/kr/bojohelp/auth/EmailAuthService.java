package kr.bojohelp.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.bojohelp.mail.EmailQueue;
import kr.bojohelp.mail.EmailTemplates;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 업체·관리자의 이메일+비밀번호 가입·로그인(API-AU01/AU02). 출연자는 문자 인증(AuthService)을 쓴다.
 * - 가입하면 이메일 확인 메일을 보낸다(업체 등록·직원 초대 수락은 확인 후에만 가능).
 * - 로그인 실패 5회(같은 계정·같은 IP, 최근 15분)면 **그 IP 에서만** 15분 막고 계정 주인에게 메일을 보낸다. 다른 IP 의 정상 로그인은 영향 없다.
 * - 존재하지 않는 이메일 시도는 DB 에 기록하지 않는다(대신 IP 단위 메모리 제한 20회/15분). 없는 이메일도 같은 연산량·같은 응답.
 * - 비밀번호 재설정으로 받은 임시 비밀번호로 로그인하면 비밀번호 변경 전용 제한 토큰만 준다.
 */
@Service
public class EmailAuthService {

    public record SignupRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank String password, @NotBlank @Size(max = 50) String name,
            List<Long> agreedTermIds, @Size(max = 100) String deviceId) {}

    /** email 칸에는 이메일 또는 관리자 아이디(예: admin)를 쓴다. secondPassword 는 관리자 계정일 때만 쓴다. */
    public record LoginRequest(@NotBlank String email, @NotBlank String password, @Size(max = 100) String secondPassword, @Size(max = 100) String deviceId) {}

    /** passwordChangeRequired 가 true 면 refreshToken 은 없고 accessToken 은 비밀번호 변경 전용이다. */
    public record LoginResult(AuthService.Tokens tokens, boolean passwordChangeRequired, boolean secondPasswordRequired) {}

    private static final Set<String> SIGNUP_REQUIRED = Set.of("SERVICE", "PRIVACY");
    static final int MAX_FAILURES = 5;
    static final int LOCK_MINUTES = 15;

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final TokenService tokens;
    private final PasswordService passwords;
    private final EmailVerificationService verification;
    private final IpFailureLimiter ipLimiter;
    private final EmailQueue mail;
    private final String dummyHash;

    public EmailAuthService(JdbcClient jdbc, PasswordEncoder encoder, AuthService auth, TokenService tokens, PasswordService passwords,
            EmailVerificationService verification, IpFailureLimiter ipLimiter, EmailQueue mail) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.auth = auth;
        this.tokens = tokens;
        this.passwords = passwords;
        this.verification = verification;
        this.ipLimiter = ipLimiter;
        this.mail = mail;
        this.dummyHash = encoder.encode("dummy-password-for-timing-0");
    }

    @Transactional
    public AuthService.Tokens signup(SignupRequest r) {
        String email = normalize(r.email());
        if (!PasswordPolicy.isAcceptable(r.password(), email)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 10자 이상, 영문과 숫자를 함께 써 주세요.");

        List<AuthService.Term> latest = auth.latestTerms();
        Set<Long> agreed = new HashSet<>(r.agreedTermIds() == null ? List.of() : r.agreedTermIds());
        Set<Long> known = new HashSet<>();
        latest.forEach(t -> known.add(t.id()));
        if (!known.containsAll(agreed)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        for (AuthService.Term t : latest) {
            if (SIGNUP_REQUIRED.contains(t.type()) && !agreed.contains(t.id())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "필수 약관에 동의해 주세요.");
        }

        long userId;
        try {
            userId = jdbc.sql("INSERT INTO users (email, password_hash, name, status, last_login_at) VALUES (:e, :h, :n, 'ACTIVE', now()) RETURNING id")
                    .param("e", email).param("h", encoder.encode(r.password())).param("n", r.name().trim()).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일이에요.");
        }
        for (Long termId : agreed) {
            jdbc.sql("INSERT INTO user_consents (user_id, term_id, agreed) VALUES (:u, :t, TRUE)").param("u", userId).param("t", termId).update();
        }
        verification.send(userId, email, r.name().trim());
        return auth.issueTokens(userId, r.deviceId());
    }

    public LoginResult login(LoginRequest r, String ip) {
        String email = normalize(r.email());
        if (ipLimiter.isBlocked(ip) || failuresFromIp(email, ip) >= MAX_FAILURES) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많아요. " + LOCK_MINUTES + "분 뒤에 다시 해 주세요.");
        }

        Optional<Object[]> user = jdbc.sql("SELECT id, password_hash, status, name FROM users WHERE email = :e AND deleted_at IS NULL").param("e", email)
                .query((rs, n) -> new Object[] {rs.getLong("id"), rs.getString("password_hash"), rs.getString("status"), rs.getString("name")}).optional();
        String hash = user.map(u -> (String) u[1]).orElse(null);
        Long userId = user.map(u -> (Long) u[0]).orElse(null);

        boolean realOk = encoder.matches(r.password(), hash == null ? dummyHash : hash) && hash != null;
        // 임시 비밀번호 확인도 계정이 있든 없든 BCrypt 비교를 한 번 한다(응답 시간으로 계정·재설정 여부가 드러나지 않게)
        boolean tempOk = false;
        if (!realOk) {
            tempOk = userId != null ? passwords.matchesTemporary(userId, r.password()) : encoder.matches(r.password(), dummyHash) && false;
        }
        if (!realOk && !tempOk) {
            ipLimiter.recordFailure(ip);
            if (userId != null) {
                record(userId, email, ip, false);
                if (failuresFromIp(email, ip) == MAX_FAILURES) {   // 막힌 순간 한 번만 계정 주인에게 알림
                    var m = EmailTemplates.loginLocked((String) user.get()[3], ClientIp.mask(ip), LOCK_MINUTES);
                    mail.enqueue(userId, email, m.subject(), m.body());
                }
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않아요.");
        }
        if (!"ACTIVE".equals(user.get()[2])) throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        // 관리자 계정은 2차 비밀번호(DB 에 고정된 값)도 맞아야 한다. 칸은 1차 비밀번호가 맞은 뒤에만 요구한다(계정 종류가 미리 드러나지 않게)
        if (isAdmin(userId)) {
            if (r.secondPassword() == null || r.secondPassword().isBlank()) return new LoginResult(null, false, true);
            String secondHash = jdbc.sql("SELECT secret_hash FROM admin_second_password WHERE id = 1").query(String.class).optional().orElse(dummyHash);
            if (!encoder.matches(r.secondPassword(), secondHash)) {
                ipLimiter.recordFailure(ip);
                record(userId, email, ip, false);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "2차 비밀번호가 맞지 않아요.");
            }
        }

        record(userId, email, ip, true);
        jdbc.sql("UPDATE users SET last_login_at = now() WHERE id = :id").param("id", userId).update();
        if (tempOk) {
            return new LoginResult(new AuthService.Tokens(tokens.limitedAccessToken(userId), tokens.accessTokenSeconds(), null), true, false);
        }
        return new LoginResult(auth.issueTokens(userId, r.deviceId()), false, false);
    }

    /** 같은 계정·같은 IP 의 최근 15분 연속 실패 횟수(그 IP 의 마지막 성공 이후). */
    private int failuresFromIp(String email, String ip) {
        return jdbc.sql("""
                SELECT count(*) FROM login_histories
                 WHERE login_id = :e AND ip_address = CAST(:ip AS inet) AND NOT success AND created_at > now() - make_interval(mins => :m)
                   AND created_at > COALESCE((SELECT max(created_at) FROM login_histories WHERE login_id = :e AND ip_address = CAST(:ip AS inet) AND success), '-infinity')
                """).param("e", email).param("ip", ip).param("m", LOCK_MINUTES).query(Integer.class).single();
    }

    private void record(long userId, String email, String ip, boolean success) {
        jdbc.sql("INSERT INTO login_histories (user_id, login_id, success, ip_address) VALUES (:u, :e, :s, CAST(:ip AS inet))")
                .param("u", userId).param("e", email).param("s", success).param("ip", ip).update();
    }

    private boolean isAdmin(long userId) {
        List<String> roles = auth.rolesOf(userId);
        return roles.contains("ADMIN") || roles.contains("SUPER_ADMIN");
    }

    /** 아이디만 쓰면(@ 없음, 예: admin) 내부 도메인을 붙인다. */
    static String normalize(String email) {
        String e = email == null ? "" : email.trim().toLowerCase();
        return !e.isEmpty() && e.indexOf('@') < 0 ? e + "@bojohelp.local" : e;
    }
}
