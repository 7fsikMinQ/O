package kr.bojohelp.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** app.auth.* 설정. 운영 프로파일은 jwtSecret·hmacPepper 에 기본값이 없어(application-prod.yml) 누락 시 기동 실패한다. */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        String jwtSecret,
        String hmacPepper,
        @DefaultValue("15") int accessTokenMinutes,
        @DefaultValue("15") int signupTokenMinutes,
        @DefaultValue("180") int otpExpireSeconds,
        @DefaultValue("5") int otpMaxAttempts,
        @DefaultValue("60") int otpResendCooldownSeconds,
        @DefaultValue("10") int otpMaxPerDay,
        @DefaultValue("90") int sessionDays,
        @DefaultValue("sms") String otpChannel) {   // sms: 문자(대행사 필요, 건당 비용) | email: 이메일 코드(무료에 가까움)

    public boolean emailChannel() {
        return "email".equalsIgnoreCase(otpChannel);
    }
}
