package kr.bojohelp.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 시험용 문자 발송기(인증번호를 기억)를 기본 발송기로 교체한다. */
@TestConfiguration
public class TestSmsConfig {
    @Bean
    @Primary
    public RecordingSmsSender recordingSmsSender() {
        return new RecordingSmsSender();
    }
}
