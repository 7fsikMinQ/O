package kr.bojohelp.auth;

import java.security.SecureRandom;
import java.util.Base64;
import kr.bojohelp.mail.EmailQueue;
import kr.bojohelp.mail.EmailTemplates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 이메일 소유 확인: 가입 때 확인 링크를 메일로 보내고, 링크의 토큰(해시만 저장, 24시간, 1회용)으로 email_verified_at 을 채운다. */
@Service
public class EmailVerificationService {

    private final JdbcClient jdbc;
    private final Hasher hasher;
    private final EmailQueue mail;
    private final String webBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(JdbcClient jdbc, Hasher hasher, EmailQueue mail, @Value("${app.web-base-url:http://localhost:5173}") String webBaseUrl) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.mail = mail;
        this.webBaseUrl = webBaseUrl;
    }

    /** 새 확인 토큰을 만들고 메일을 큐에 넣는다. 호출하는 쪽 트랜잭션 안에서 실행된다. */
    public void send(long userId, String email, String name) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("INSERT INTO email_verifications (user_id, token_hash, expires_at) VALUES (:u, :h, now() + interval '24 hours')")
                .param("u", userId).param("h", hasher.hash("ev:" + token)).update();
        var m = EmailTemplates.verifyEmail(name, webBaseUrl + "/verify-email?token=" + token);
        mail.enqueue(userId, email, m.subject(), m.body());
    }

    /** 재발송: 이미 확인했으면 409, 1분에 1번·하루 5번까지(초과 429). */
    @Transactional
    public void resend(long userId) {
        var u = jdbc.sql("SELECT email, name, email_verified_at IS NOT NULL FROM users WHERE id = :id").param("id", userId)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getBoolean(3)}).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (u[0] == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "이메일이 없는 계정이에요.");
        if ((Boolean) u[2]) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 확인된 이메일이에요.");
        int lastMinute = jdbc.sql("SELECT count(*) FROM email_verifications WHERE user_id = :u AND created_at > now() - interval '1 minute'").param("u", userId).query(Integer.class).single();
        int today = jdbc.sql("SELECT count(*) FROM email_verifications WHERE user_id = :u AND created_at > now() - interval '24 hours'").param("u", userId).query(Integer.class).single();
        if (lastMinute > 0 || today >= 5) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 뒤에 다시 요청해 주세요.");
        send(userId, (String) u[0], (String) u[1]);
    }

    @Transactional
    public void verify(String token) {
        if (token == null || token.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var row = jdbc.sql("SELECT id, user_id FROM email_verifications WHERE token_hash = :h AND used_at IS NULL AND expires_at > now() FOR UPDATE").param("h", hasher.hash("ev:" + token))
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "확인 링크가 만료되었거나 이미 사용했어요."));
        jdbc.sql("UPDATE email_verifications SET used_at = now() WHERE id = :id").param("id", row[0]).update();
        jdbc.sql("UPDATE users SET email_verified_at = COALESCE(email_verified_at, now()) WHERE id = :u").param("u", row[1]).update();
    }
}
