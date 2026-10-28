package kr.bojohelp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhoneNumberTest {

    @Test
    void normalizesCommonFormats() {
        assertThat(PhoneNumber.normalize("010-1234-5678")).isEqualTo("01012345678");
        assertThat(PhoneNumber.normalize(" 010 1234 5678 ")).isEqualTo("01012345678");
        assertThat(PhoneNumber.normalize("+82 10-1234-5678")).isEqualTo("01012345678");
        assertThat(PhoneNumber.normalize("011-234-5678")).isEqualTo("0112345678");
    }

    @Test
    void rejectsNonMobile() {
        assertThatThrownBy(() -> PhoneNumber.normalize("02-1234-5678")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PhoneNumber.normalize("010-12")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PhoneNumber.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void masksMiddleDigits() {
        assertThat(PhoneNumber.mask("01012345678")).isEqualTo("010****5678");
    }
}
