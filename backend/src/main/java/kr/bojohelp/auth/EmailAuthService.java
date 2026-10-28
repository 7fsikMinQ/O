package kr.bojohelp.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 업체·관리자의 이메일+비밀번호 가입·로그인(API-AU01/AU02). 출연자는 문자 인증(AuthService)을 쓴다.
 * 로그인 실패 5회(최근 15분, 마지막 성공 이후)면 잠그고, 없는 이메일도 같은 시간·같은 응답으로 처리해 계정 존재 여부를 숨긴다.
 * 이메일 소유 확인(메일 인증)은 메일 발송 슬라이스에서 추가한다 — 그 전까지 업체는 관리자 승인 절차가 사람 확인을 대신한다.
 */
@Service
public class EmailAuthService {

    public record SignupRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank String password, @NotBlank @Size(max = 50) String name,
            List<Long> agreedTermIds, @Size(max = 100) String deviceId) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password, @Size(max = 100) String deviceId) {}

    private static final Set<String> SIGNUP_REQUIRED = Set.of("SERVICE", "PRIVACY");
    private static final int MAX_FAILURES = 5;

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final String dummyHash;

    public EmailAuthService(JdbcClient jdbc, PasswordEncoder encoder, AuthService auth) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.auth = auth;
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
        return auth.issueTokens(userId, r.deviceId());
    }

    public AuthService.Tokens login(LoginRequest r) {
        String email = normalize(r.email());
        if (isLocked(email)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많아요. 잠시 뒤에 다시 해 주세요.");

        Optional<Object[]> user = jdbc.sql("SELECT id, password_hash, status FROM users WHERE email = :e AND deleted_at IS NULL").param("e", email)
                .query((rs, n) -> new Object[] {rs.getLong("id"), rs.getString("password_hash"), rs.getString("status")}).optional();
        String hash = user.map(u -> (String) u[1]).orElse(null);
        boolean ok = encoder.matches(r.password(), hash == null ? dummyHash : hash) && user.isPresent() && hash != null;   // 없는 이메일도 같은 연산량
        Long userId = user.map(u -> (Long) u[0]).orElse(null);
        record(userId, email, ok);
        if (!ok) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않아요.");
        if (!"ACTIVE".equals(user.get()[2])) throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        jdbc.sql("UPDATE users SET last_login_at = now() WHERE id = :id").param("id", userId).update();
        return auth.issueTokens(userId, r.deviceId());
    }

    private boolean isLocked(String email) {
        int failures = jdbc.sql("""
                SELECT count(*) FROM login_histories
                 WHERE login_id = :e AND NOT success AND created_at > now() - interval '15 minutes'
                   AND created_at > COALESCE((SELECT max(created_at) FROM login_histories WHERE login_id = :e AND success), '-infinity')
                """).param("e", email).query(Integer.class).single();
        return failures >= MAX_FAILURES;
    }

    private void record(Long userId, String email, boolean success) {
        jdbc.sql("INSERT INTO login_histories (user_id, login_id, success) VALUES (:u, :e, :s)").param("u", userId).param("e", email).param("s", success).update();
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
