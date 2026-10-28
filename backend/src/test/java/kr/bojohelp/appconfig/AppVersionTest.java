package kr.bojohelp.appconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AppVersionTest {

    @Test
    void comparesNumerically() {
        assertThat(AppVersion.compare("1.0.9", "1.0.10")).isNegative();
        assertThat(AppVersion.compare("1.2", "1.2.0")).isZero();
        assertThat(AppVersion.compare("2.0.0", "1.9.9")).isPositive();
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> AppVersion.compare("abc", "1.0.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppVersion.compare("1.0.0-beta", "1.0.0")).isInstanceOf(IllegalArgumentException.class);
    }
}
