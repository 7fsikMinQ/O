package kr.bojohelp.company;

import static org.assertj.core.api.Assertions.assertThat;

import kr.bojohelp.auth.PasswordPolicy;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void enforcesLengthLettersAndDigits() {
        assertThat(PasswordPolicy.isAcceptable("abcdefgh12", "a@b.c")).isTrue();
        assertThat(PasswordPolicy.isAcceptable("short1", "a@b.c")).isFalse();          // 짧음
        assertThat(PasswordPolicy.isAcceptable("onlyletterslong", "a@b.c")).isFalse();  // 숫자 없음
        assertThat(PasswordPolicy.isAcceptable("1234567890123", "a@b.c")).isFalse();    // 영문 없음
        assertThat(PasswordPolicy.isAcceptable("a".repeat(80) + "1", "a@b.c")).isFalse();   // BCrypt 72바이트 초과
        assertThat(PasswordPolicy.isAcceptable("same@b.c1234", "SAME@B.C1234")).isFalse();   // 이메일과 동일
        assertThat(PasswordPolicy.isAcceptable(null, null)).isFalse();
    }
}
