package kr.bojohelp.crypto;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** app.crypto.*: 필드 암호화 키(버전별 base64, 32바이트)와 현재 사용 버전. 운영 프로파일은 키에 기본값이 없다. */
@ConfigurationProperties(prefix = "app.crypto")
public record CryptoProperties(@DefaultValue("1") int activeVersion, Map<Integer, String> keys) {}
