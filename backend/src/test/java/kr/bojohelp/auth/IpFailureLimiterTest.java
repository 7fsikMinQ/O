package kr.bojohelp.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class IpFailureLimiterTest {

    @Test
    void blocksAfterTwentyFailuresAndReleasesAfterFifteenMinutes() {
        AtomicLong now = new AtomicLong(1_000_000);
        IpFailureLimiter l = new IpFailureLimiter(now::get);
        for (int i = 0; i < 19; i++) l.recordFailure("1.1.1.1");
        assertThat(l.isBlocked("1.1.1.1")).isFalse();
        l.recordFailure("1.1.1.1");
        assertThat(l.isBlocked("1.1.1.1")).isTrue();
        assertThat(l.isBlocked("2.2.2.2")).isFalse();             // 다른 IP 는 영향 없음
        now.addAndGet(IpFailureLimiter.WINDOW_MILLIS + 1);
        assertThat(l.isBlocked("1.1.1.1")).isFalse();             // 15분 뒤 풀림
    }
}
