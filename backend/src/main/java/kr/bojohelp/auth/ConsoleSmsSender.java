package kr.bojohelp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 개발 전용: 문자를 보내지 않고 서버 로그에 찍는다. app.sms.provider=console 일 때만 등록되고 운영 프로파일은 이 값을 쓰지 않는다. */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "console")
class ConsoleSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSmsSender.class);

    @Override
    public void send(String normalizedPhone, String message) {
        log.info("[DEV SMS] to={} message={}", PhoneNumber.mask(normalizedPhone), message);
    }
}
