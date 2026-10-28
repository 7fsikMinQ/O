package kr.bojohelp.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import kr.bojohelp.mail.EmailQueue;
import kr.bojohelp.mail.EmailTemplates;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 인증번호 발급·검증(otp_challenges). 채널은 문자(sms) 또는 이메일(email). 식별자·코드는 해시만 저장하고 로그에는 마스킹된 번호만 남긴다. */
@Service
public class OtpService {

    public enum Result { OK, INVALID, TOO_MANY }

    private static final String PURPOSE = "LOGIN";   // 가입·로그인 공용(번호가 없으면 가입 단계로 이어짐)
    private final JdbcClient jdbc;
    private final Hasher hasher;
    private final SmsSender sms;
    private final EmailQueue mail;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    public OtpService(JdbcClient jdbc, Hasher hasher, SmsSender sms, EmailQueue mail, AuthProperties props) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.sms = sms;
        this.mail = mail;
        this.props = props;
    }

    /** 발송 제한: 재전송 대기(기본 60초), 하루 최대(기본 10회). */
    public int send(String rawPhone, String rawEmail) {
        String id = normalizeIdentifier(rawPhone, rawEmail);
        String ph = key(id);

        int recent = jdbc.sql("SELECT count(*) FROM otp_challenges WHERE phone_hash = :h AND created_at > now() - make_interval(secs => :s)")
                .param("h", ph).param("s", props.otpResendCooldownSeconds()).query(Integer.class).single();
        int today = jdbc.sql("SELECT count(*) FROM otp_challenges WHERE phone_hash = :h AND created_at > now() - interval '24 hours'")
                .param("h", ph).query(Integer.class).single();
        if ((props.otpResendCooldownSeconds() > 0 && recent > 0) || today >= props.otpMaxPerDay()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        jdbc.sql("INSERT INTO otp_challenges (phone_hash, purpose, code_hash, expires_at) VALUES (:h, :p, :c, now() + make_interval(secs => :e))")
                .param("h", ph).param("p", PURPOSE).param("c", hasher.hash("otp:" + ph + ":" + code)).param("e", props.otpExpireSeconds())
                .update();
        if (props.emailChannel()) {
            var m = EmailTemplates.otpCode(code, props.otpExpireSeconds() / 60);
            mail.enqueueAnonymous(id, m.subject(), m.body());
        } else {
            sms.send(id, "[보조출연 헬프] 인증번호 " + code + " (" + (props.otpExpireSeconds() / 60) + "분 안에 입력해 주세요)");
        }
        return props.otpExpireSeconds();
    }

    /** 가장 최근의 유효한 인증번호 1건과 비교. 틀리면 시도 횟수를 올리고, 상한을 넘으면 TOO_MANY. */
    public Result verify(String rawPhone, String rawEmail, String code) {
        String ph = key(normalizeIdentifier(rawPhone, rawEmail));
        var row = jdbc.sql("""
                SELECT id, code_hash, attempts FROM otp_challenges
                 WHERE phone_hash = :h AND purpose = :p AND consumed_at IS NULL AND expires_at > now()
                 ORDER BY id DESC LIMIT 1
                """)
                .param("h", ph).param("p", PURPOSE)
                .query((rs, n) -> new long[] {rs.getLong("id"), rs.getInt("attempts")}).optional();
        if (row.isEmpty()) return Result.INVALID;
        long id = row.get()[0];
        if (row.get()[1] >= props.otpMaxAttempts()) return Result.TOO_MANY;

        String expected = jdbc.sql("SELECT code_hash FROM otp_challenges WHERE id = :id").param("id", id).query(String.class).single();
        String given = hasher.hash("otp:" + ph + ":" + (code == null ? "" : code));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            jdbc.sql("UPDATE otp_challenges SET attempts = attempts + 1 WHERE id = :id").param("id", id).update();
            return Result.INVALID;
        }
        int updated = jdbc.sql("UPDATE otp_challenges SET consumed_at = now() WHERE id = :id AND consumed_at IS NULL").param("id", id).update();
        return updated == 1 ? Result.OK : Result.INVALID;   // 동시 재사용 방지
    }

    /** 설정된 채널에 맞는 식별자(정규화된 번호 또는 소문자 이메일). 형식이 틀리면 400. */
    public String normalizeIdentifier(String rawPhone, String rawEmail) {
        try {
            if (props.emailChannel()) {
                String e = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
                if (e.length() > 254 || !e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new IllegalArgumentException();
                return e;
            }
            return PhoneNumber.normalize(rawPhone);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, props.emailChannel() ? "이메일 주소를 확인해 주세요." : "휴대폰 번호를 확인해 주세요.");
        }
    }

    private String key(String id) {
        return hasher.hash((props.emailChannel() ? "email:" : "phone:") + id);
    }
}
