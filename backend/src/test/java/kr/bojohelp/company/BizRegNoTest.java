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

    @Test
    void 선택_입력은_비면_null_있으면_10자리만_본다() {
        assertThat(BizRegNo.normalizeOptional(null)).isNull();
        assertThat(BizRegNo.normalizeOptional("  ")).isNull();
        assertThat(BizRegNo.normalizeOptional("123-45-67890")).isEqualTo("123-45-67890");
        assertThatThrownBy(() -> BizRegNo.normalizeOptional("12345")).isInstanceOf(IllegalArgumentException.class);
    }
}
