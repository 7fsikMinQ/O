package kr.bojohelp.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** app.storage.*: 개발은 local(서버 디스크), 운영은 R2 어댑터를 추가해 provider 만 바꾼다. */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        @DefaultValue("local") String provider,
        @DefaultValue("./data/files") String localDir,
        @DefaultValue("300") int urlTtlSeconds,
        @DefaultValue("1600") int maxImagePixels,
        @DefaultValue("5") int maxPhotosPerKind) {}
