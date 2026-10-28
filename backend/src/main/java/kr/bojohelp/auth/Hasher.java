package kr.bojohelp.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** HMAC-SHA256(pepper) 16진 문자열. 전화번호 해시·인증번호 해시·리프레시 토큰 해시에 쓴다(평문 저장 ✕). */
@Component
public class Hasher {

    private final SecretKeySpec key;

    public Hasher(AuthProperties props) {
        if (props.hmacPepper() == null || props.hmacPepper().length() < 16) {
            throw new IllegalStateException("app.auth.hmac-pepper must be at least 16 characters");
        }
        this.key = new SecretKeySpec(props.hmacPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String hash(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
