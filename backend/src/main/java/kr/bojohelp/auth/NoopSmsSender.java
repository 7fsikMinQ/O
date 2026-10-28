package kr.bojohelp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 문자 대행사를 아직 연결하지 않았을 때 쓰는 자리표시자(app.sms.provider=none). 요청은 정상 완료되지만 실제로 보내지 않는다.
 * 서버가 운영 모드에서도 기동되게 하는 용도이며, 대행사를 연결할 때는 SmsSender 구현을 하나 추가하고 provider 값만 바꾼다.
 * 문자 비용이 들지 않는 이메일 채널(app.auth.otp-channel=email)을 쓰면 이 클래스는 쓰이지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "none")
class NoopSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(NoopSmsSender.class);

    @Override
    public void send(String normalizedPhone, String message) {
        log.warn("문자 발송 대행사가 연결되지 않아 보내지 않았습니다 (to={})", PhoneNumber.mask(normalizedPhone));
    }
}
