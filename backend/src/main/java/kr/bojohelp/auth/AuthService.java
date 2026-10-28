package kr.bojohelp.auth;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 출연자 휴대폰 가입·로그인(REQ-A10): 번호 인증 → 기존 회원이면 로그인, 아니면 가입 토큰 → 이름·약관 동의로 가입 완료. */
@Service
public class AuthService {

    public record Tokens(String accessToken, long expiresIn, String refreshToken) {}

    public record VerifyOutcome(Tokens tokens, String signupToken) {}

    public record Term(long id, String type, String version, String content, boolean required) {}

    /** 가입 때 반드시 동의해야 하는 약관(주민번호·전자서명 동의는 첫 지원 때). */
    private static final Set<String> SIGNUP_REQUIRED = Set.of("SERVICE", "PRIVACY", "THIRD_PARTY_PROVISION");

    private final JdbcClient jdbc;
    private final OtpService otp;
    private final TokenService tokens;
    private final RefreshTokenService refresh;
    private final AuthProperties props;

    public AuthService(JdbcClient jdbc, OtpService otp, TokenService tokens, RefreshTokenService refresh, AuthProperties props) {
        this.props = props;
        this.jdbc = jdbc;
        this.otp = otp;
        this.tokens = tokens;
        this.refresh = refresh;
    }

    public VerifyOutcome verifyOtp(String rawPhone, String rawEmail, String code, String deviceId) {
        OtpService.Result r = otp.verify(rawPhone, rawEmail, code);
        if (r == OtpService.Result.TOO_MANY) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        if (r != OtpService.Result.OK) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);

        String id = otp.normalizeIdentifier(rawPhone, rawEmail);
        boolean byEmail = props.emailChannel();
        Optional<Object[]> user = jdbc.sql("SELECT id, status FROM users WHERE " + (byEmail ? "email" : "phone") + " = :p AND deleted_at IS NULL")
                .param("p", id).query((rs, n) -> new Object[] {rs.getLong("id"), rs.getString("status")}).optional();
        if (user.isEmpty()) return new VerifyOutcome(null, tokens.signupToken(id));
        if (!"ACTIVE".equals(user.get()[1])) throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        long userId = (Long) user.get()[0];
        // 이메일 코드만으로는 업체·관리자 계정(비밀번호·2차 확인 필요)에 들어올 수 없다: 출연자 전용 계정만 허용
        if (byEmail && !rolesOf(userId).equals(List.of("ACTOR"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "업체·관리자로 가입된 이메일이에요. 이메일과 비밀번호로 로그인해 주세요.");
        }
        jdbc.sql("UPDATE users SET last_login_at = now(), " + (byEmail ? "email_verified_at = COALESCE(email_verified_at, now())" : "phone_verified_at = COALESCE(phone_verified_at, now())") + " WHERE id = :id").param("id", userId).update();
        return new VerifyOutcome(issueTokens(userId, deviceId), null);
    }

    @Transactional
    public Tokens completeSignup(String signupToken, String name, List<Long> agreedTermIds, String deviceId) {
        String identifier = tokens.phoneFromSignupToken(signupToken);   // 번호 또는 이메일(채널에 따라)
        if (identifier == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        List<Term> latest = latestTerms();
        Set<Long> agreed = new HashSet<>(agreedTermIds == null ? List.of() : agreedTermIds);
        Set<Long> known = new HashSet<>();
        latest.forEach(t -> known.add(t.id()));
        if (!known.containsAll(agreed)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        for (Term t : latest) {
            if (SIGNUP_REQUIRED.contains(t.type()) && !agreed.contains(t.id())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }

        long userId;
        try {
            userId = props.emailChannel()
                    ? jdbc.sql("INSERT INTO users (email, name, status, email_verified_at, last_login_at) VALUES (:p, :n, 'ACTIVE', now(), now()) RETURNING id")
                            .param("n", name.trim()).param("p", identifier).query(Long.class).single()
                    : jdbc.sql("INSERT INTO users (email, name, phone, status, phone_verified_at, last_login_at) VALUES (NULL, :n, :p, 'ACTIVE', now(), now()) RETURNING id")
                            .param("n", name.trim()).param("p", identifier).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) SELECT :u, id FROM roles WHERE code = 'ACTOR'").param("u", userId).update();
        for (Long termId : agreed) {
            jdbc.sql("INSERT INTO user_consents (user_id, term_id, agreed) VALUES (:u, :t, TRUE)").param("u", userId).param("t", termId).update();
        }
        return issueTokens(userId, deviceId);
    }

    /** 리프레시 토큰 회전 → 새 액세스·리프레시 토큰. 실패 시 401. */
    public Tokens refresh(String refreshToken, String deviceId) {
        var rotated = refresh.rotate(refreshToken, deviceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        String status = jdbc.sql("SELECT status FROM users WHERE id = :id AND deleted_at IS NULL").param("id", rotated.userId()).query(String.class).optional().orElse("");
        if (!"ACTIVE".equals(status)) {
            refresh.revokeAll(rotated.userId());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return new Tokens(tokens.accessToken(rotated.userId(), rolesOf(rotated.userId())), tokens.accessTokenSeconds(), rotated.newToken());
    }

    public void logout(String refreshToken) {
        refresh.revoke(refreshToken);
    }

    public List<Term> latestTerms() {
        return jdbc.sql("""
                SELECT DISTINCT ON (type) id, type, version, content, is_required
                  FROM terms WHERE effective_at <= now() ORDER BY type, effective_at DESC
                """)
                .query((rs, n) -> new Term(rs.getLong("id"), rs.getString("type"), rs.getString("version"), rs.getString("content"), rs.getBoolean("is_required")))
                .list();
    }

    public List<String> rolesOf(long userId) {
        return jdbc.sql("SELECT r.code FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = :u ORDER BY r.code")
                .param("u", userId).query(String.class).list();
    }

    public Tokens issueTokens(long userId, String deviceId) {
        return new Tokens(tokens.accessToken(userId, rolesOf(userId)), tokens.accessTokenSeconds(), refresh.issue(userId, deviceId));
    }
}
