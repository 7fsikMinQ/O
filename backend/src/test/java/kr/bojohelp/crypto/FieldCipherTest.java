package kr.bojohelp.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldCipherTest {

    private static String key(char c) {
        return Base64.getEncoder().encodeToString(String.valueOf(c).repeat(32).getBytes());
    }

    private static FieldCipher cipher(int active, Map<Integer, String> keys) {
        return new FieldCipher(new CryptoProperties(active, keys));
    }

    @Test
    void roundTripAndRandomisedCiphertext() {
        FieldCipher c = cipher(1, Map.of(1, key('a')));
        String a = c.encrypt("9001011234567");
        String b = c.encrypt("9001011234567");
        assertThat(a).startsWith("v1:").doesNotContain("9001011234567").isNotEqualTo(b);   // 같은 평문도 매번 다른 암호문
        assertThat(c.decrypt(a)).isEqualTo("9001011234567");
    }

    @Test
    void tamperedCiphertextIsRejectedWithoutLeakingContent() {
        FieldCipher c = cipher(1, Map.of(1, key('a')));
        String token = c.encrypt("secret-value");
        char last = token.charAt(token.length() - 3);
        String broken = token.substring(0, token.length() - 3) + (last == 'A' ? 'B' : 'A') + token.substring(token.length() - 2);
        assertThatThrownBy(() -> c.decrypt(broken)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret");
    }

    @Test
    void oldKeyVersionStillDecryptsAfterRotation() {
        String oldToken = cipher(1, Map.of(1, key('a'))).encrypt("rotate-me");
        FieldCipher rotated = cipher(2, Map.of(1, key('a'), 2, key('b')));
        assertThat(rotated.encrypt("x")).startsWith("v2:");
        assertThat(rotated.decrypt(oldToken)).isEqualTo("rotate-me");
    }

    @Test
    void wrongKeyFails() {
        String token = cipher(1, Map.of(1, key('a'))).encrypt("x");
        assertThatThrownBy(() -> cipher(1, Map.of(1, key('b'))).decrypt(token)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBadKeyConfig() {
        assertThatThrownBy(() -> cipher(1, Map.of(1, Base64.getEncoder().encodeToString("short".getBytes())))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(2, Map.of(1, key('a')))).isInstanceOf(IllegalStateException.class);
    }
}
