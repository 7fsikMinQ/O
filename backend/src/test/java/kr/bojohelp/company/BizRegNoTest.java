package kr.bojohelp.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BizRegNoTest {

    @Test
    void acceptsValidNumbersInAnyFormat() {
        assertThat(BizRegNo.normalize("220-81-62517")).isEqualTo("220-81-62517");   // 공개된 상장사 사업자번호로 검증식 확인
        assertThat(BizRegNo.normalize("2208162517")).isEqualTo("220-81-62517");
        assertThat(BizRegNo.normalize(" 220 81 62517 ")).isEqualTo("220-81-62517");
    }

    @Test
    void rejectsWrongCheckDigitAndBadFormat() {
        assertThatThrownBy(() -> BizRegNo.normalize("220-81-62518")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BizRegNo.normalize("123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BizRegNo.normalize("abcdefghij")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BizRegNo.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
