package kr.bojohelp.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 서버 시각은 UTC, 표시는 클라이언트가 KST 로 변환한다(CLAUDE.md §7). 테스트에서 Clock 을 교체할 수 있게 빈으로 둔다. */
@Configuration
public class ClockConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
