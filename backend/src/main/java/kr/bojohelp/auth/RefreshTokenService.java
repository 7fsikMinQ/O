package kr.bojohelp.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리프레시 토큰: 무작위 32바이트, DB 에는 해시만 저장, 사용할 때마다 회전(이전 것 폐기).
 * 이미 폐기된 토큰이 다시 오면 탈취로 보고 그 사용자의 모든 토큰을 폐기한다.
 */
@Service
public class RefreshTokenService {

    public record Rotated(long userId, String newToken) {}

    private final JdbcClient jdbc;
    private final Hasher hasher;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(JdbcClient jdbc, Hasher hasher, AuthProperties props) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.props = props;
    }

    public String issue(long userId, String deviceId) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("INSERT INTO refresh_tokens (user_id, token_hash, device_id, expires_at) VALUES (:u, :h, :d, now() + make_interval(days => :days))")
                .param("u", userId).param("h", hasher.hash("rt:" + token)).param("d", deviceId).param("days", props.sessionDays()).update();
        return token;
    }

    /** 성공하면 새 토큰, 실패(없음·만료·재사용)면 빈 값. 재사용 감지 시 폐기 처리가 커밋되도록 예외를 던지지 않는다. */
    @Transactional
    public Optional<Rotated> rotate(String token, String deviceId) {
        if (token == null || token.isBlank()) return Optional.empty();
        var row = jdbc.sql("SELECT id, user_id, revoked_at IS NOT NULL AS revoked, expires_at < now() AS expired FROM refresh_tokens WHERE token_hash = :h FOR UPDATE")
                .param("h", hasher.hash("rt:" + token))
                .query((rs, n) -> new Object[] {rs.getLong("id"), rs.getLong("user_id"), rs.getBoolean("revoked"), rs.getBoolean("expired")})
                .optional();
        if (row.isEmpty()) return Optional.empty();
        long id = (Long) row.get()[0];
        long userId = (Long) row.get()[1];
        if ((Boolean) row.get()[2]) {
            revokeAll(userId);
            return Optional.empty();
        }
        if ((Boolean) row.get()[3]) return Optional.empty();
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE id = :id").param("id", id).update();
        return Optional.of(new Rotated(userId, issue(userId, deviceId)));
    }

    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = :h AND revoked_at IS NULL")
                .param("h", hasher.hash("rt:" + token)).update();
    }

    public void revokeAll(long userId) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", userId).update();
    }
}
