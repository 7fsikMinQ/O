package kr.bojohelp.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import kr.bojohelp.mail.EmailQueue;
import kr.bojohelp.mail.EmailTemplates;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 비밀번호 재설정과 변경.
 * 재설정: 이메일로 임시 비밀번호(30분, 해시만 저장)를 보낸다. 기존 비밀번호는 그대로라 남이 재설정을 눌러도 계정이 잠기거나 바뀌지 않는다.
 *         임시 비밀번호로 로그인하면 변경 전용 제한 토큰만 받고, 새 비밀번호로 바꾸면 임시 비밀번호는 폐기되고 모든 기기에서 로그아웃된다.
 * 요청 응답은 계정 유무와 상관없이 항상 같다(202). 이메일당 1시간 3회, IP당 1시간 10회를 넘으면 조용히 보내지 않는다.
 */
@Service
public class PasswordService {

    public record ResetRequest(@NotBlank @Email String email) {}

    public record ChangeRequest(@Size(max = 72) String currentPassword, @NotBlank @Size(max = 72) String newPassword) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final EmailQueue mail;
    private final AuthService auth;
    private final RefreshTokenService refresh;
    private final String dummyHash;

    public PasswordService(JdbcClient jdbc, PasswordEncoder encoder, EmailQueue mail, AuthService auth, RefreshTokenService refresh) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mail = mail;
        this.auth = auth;
        this.refresh = refresh;
        this.dummyHash = encoder.encode("dummy-temp-password-0");
    }

    @Transactional
    public void requestReset(String rawEmail, String ip) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        Optional<Object[]> user = jdbc.sql("SELECT id, name FROM users WHERE email = :e AND password_hash IS NOT NULL AND status = 'ACTIVE' AND deleted_at IS NULL").param("e", email)
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)}).optional();
        if (user.isEmpty()) return;
        long userId = (Long) user.get()[0];
        int byUser = jdbc.sql("SELECT count(*) FROM password_resets WHERE user_id = :u AND created_at > now() - interval '1 hour'").param("u", userId).query(Integer.class).single();
        int byIp = jdbc.sql("SELECT count(*) FROM password_resets WHERE requested_ip = CAST(:ip AS inet) AND created_at > now() - interval '1 hour'").param("ip", ip).query(Integer.class).single();
        if (byUser >= 3 || byIp >= 10) return;

        jdbc.sql("UPDATE password_resets SET used_at = now() WHERE user_id = :u AND used_at IS NULL").param("u", userId).update();
        String temp = PasswordGenerator.temporary();
        jdbc.sql("INSERT INTO password_resets (user_id, temp_hash, expires_at, requested_ip) VALUES (:u, :h, now() + interval '30 minutes', CAST(:ip AS inet))")
                .param("u", userId).param("h", encoder.encode(temp)).param("ip", ip).update();
        var m = EmailTemplates.temporaryPassword((String) user.get()[1], temp);
        mail.enqueue(userId, email, m.subject(), m.body());
    }

    /** 유효한 임시 비밀번호와 맞는지. 있든 없든 BCrypt 비교를 한 번 한다(응답 시간으로 재설정 여부가 드러나지 않게). */
    public boolean matchesTemporary(long userId, String password) {
        Optional<String> hash = jdbc.sql("SELECT temp_hash FROM password_resets WHERE user_id = :u AND used_at IS NULL AND expires_at > now() ORDER BY id DESC LIMIT 1").param("u", userId).query(String.class).optional();
        boolean ok = encoder.matches(password, hash.orElse(dummyHash));
        return ok && hash.isPresent();
    }

    @Transactional
    public AuthService.Tokens change(long userId, boolean viaTemporaryLogin, ChangeRequest r) {
        var u = jdbc.sql("SELECT email, name, password_hash FROM users WHERE id = :id AND deleted_at IS NULL").param("id", userId)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (u[2] == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "비밀번호를 쓰지 않는 계정이에요.");
        if (!viaTemporaryLogin && (r.currentPassword() == null || !encoder.matches(r.currentPassword(), u[2]))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "현재 비밀번호가 맞지 않아요.");
        }
        if (!PasswordPolicy.isAcceptable(r.newPassword(), u[0])) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 10자 이상, 영문과 숫자를 함께 써 주세요.");
        if (encoder.matches(r.newPassword(), u[2])) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이전과 다른 비밀번호를 써 주세요.");

        jdbc.sql("UPDATE users SET password_hash = :h, updated_at = now() WHERE id = :id").param("h", encoder.encode(r.newPassword())).param("id", userId).update();
        jdbc.sql("UPDATE password_resets SET used_at = now() WHERE user_id = :u AND used_at IS NULL").param("u", userId).update();
        refresh.revokeAll(userId);   // 모든 기기에서 로그아웃
        if (u[0] != null) {
            var m = EmailTemplates.passwordChanged(u[1]);
            mail.enqueue(userId, u[0], m.subject(), m.body());
        }
        return auth.issueTokens(userId, null);
    }
}
