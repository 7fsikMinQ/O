package kr.bojohelp.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 주민번호·계좌번호 같은 민감 필드의 AES-256-GCM 암호화. 결과 문자열은 "v{키버전}:{base64(iv12 + 암호문 + 태그16)}" 형식이라
 * 키를 교체해도(새 버전 추가) 이전 버전으로 만든 값을 계속 복호화할 수 있다. 같은 평문도 매번 다른 암호문이 나온다.
 * 복호화 실패(변조·잘못된 키) 시 예외에는 원문·암호문을 담지 않는다.
 */
@Component
public class FieldCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<Integer, SecretKey> keys = new HashMap<>();
    private final int activeVersion;
    private final SecureRandom random = new SecureRandom();

    public FieldCipher(CryptoProperties props) {
        if (props.keys() == null || props.keys().isEmpty()) throw new IllegalStateException("app.crypto.keys must not be empty");
        props.keys().forEach((version, b64) -> {
            byte[] raw = Base64.getDecoder().decode(b64);
            if (raw.length != 32) throw new IllegalStateException("app.crypto.keys." + version + " must be 32 bytes (base64)");
            keys.put(version, new SecretKeySpec(raw, "AES"));
        });
        if (!keys.containsKey(props.activeVersion())) throw new IllegalStateException("active crypto key version is missing");
        this.activeVersion = props.activeVersion();
    }

    public int activeVersion() {
        return activeVersion;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.get(activeVersion), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct);
            return "v" + activeVersion + ":" + Base64.getEncoder().encodeToString(buf.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed");
        }
    }

    public String decrypt(String token) {
        try {
            int colon = token.indexOf(':');
            if (colon < 2 || token.charAt(0) != 'v') throw new IllegalArgumentException();
            SecretKey key = keys.get(Integer.parseInt(token.substring(1, colon)));
            if (key == null) throw new IllegalArgumentException();
            byte[] all = Base64.getDecoder().decode(token.substring(colon + 1));
            if (all.length < IV_BYTES + TAG_BITS / 8) throw new IllegalArgumentException();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("decryption failed");
        }
    }
}
