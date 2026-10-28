package kr.bojohelp.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RrnPolicyTest {

    @Test
    void parsesBirthDateAndGender() {
        var p = RrnPolicy.parse("900101-1234567");
        assertThat(p.birthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(p.gender()).isEqualTo("MALE");
        assertThat(RrnPolicy.parse("0506153234567").birthDate()).isEqualTo(LocalDate.of(2005, 6, 15));
        assertThat(RrnPolicy.parse("0506154234567").gender()).isEqualTo("FEMALE");
    }

    @Test
    void rejectsBadFormats() {
        for (String bad : new String[] {null, "", "12345", "900101-12345678", "900230-1234567", "900101-5a34567", "9001011934567".replace('9', 'x')}) {
            assertThatThrownBy(() -> RrnPolicy.parse(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void errorMessagesDoNotContainTheValue() {
        assertThatThrownBy(() -> RrnPolicy.parse("900230-1234567")).hasMessageNotContaining("900230");
    }

    @Test
    void masksAllButFirstSevenDigits() {
        assertThat(RrnPolicy.mask("9001011234567")).isEqualTo("900101-1******");
    }
}
