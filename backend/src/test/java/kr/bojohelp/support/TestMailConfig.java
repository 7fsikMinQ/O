package kr.bojohelp.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestMailConfig {
    @Bean
    @Primary
    public RecordingEmailSender recordingEmailSender() {
        return new RecordingEmailSender();
    }
}
