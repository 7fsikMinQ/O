package kr.bojohelp.file;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.UUID;
import kr.bojohelp.auth.Hasher;
import org.springframework.stereotype.Component;

/** 로컬 저장소용 짧은 수명 서명 URL: /files/{publicId}?exp=…&sig=HMAC. 운영(R2)은 R2 의 서명 URL 로 대체한다. */
@Component
public class SignedUrls {

    private final Hasher hasher;
    private final Clock clock;
    private final StorageProperties props;

    public SignedUrls(Hasher hasher, Clock clock, StorageProperties props) {
        this.hasher = hasher;
        this.clock = clock;
        this.props = props;
    }

    public String urlFor(UUID publicId) {
        long exp = clock.instant().getEpochSecond() + props.urlTtlSeconds();
        return "/files/" + publicId + "?exp=" + exp + "&sig=" + sign(publicId, exp);
    }

    public boolean isValid(UUID publicId, long exp, String sig) {
        if (sig == null || exp < clock.instant().getEpochSecond()) return false;
        return MessageDigest.isEqual(sign(publicId, exp).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(UUID publicId, long exp) {
        return hasher.hash("file:" + publicId + ":" + exp);
    }
}
