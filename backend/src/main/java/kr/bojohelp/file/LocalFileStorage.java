package kr.bojohelp.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 개발용 로컬 디스크 저장소. 경로 탈출(../)을 막는다. 운영에서는 사용하지 않는다(R2 어댑터 예정). */
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
class LocalFileStorage implements FileStorage {

    private final Path base;

    LocalFileStorage(StorageProperties props) {
        this.base = Path.of(props.localDir()).toAbsolutePath().normalize();
    }

    @Override
    public String provider() {
        return "LOCAL";
    }

    @Override
    public String bucket() {
        return "local";
    }

    private Path resolve(String key) {
        Path p = base.resolve(key).normalize();
        if (!p.startsWith(base)) throw new IllegalArgumentException("invalid key");
        return p;
    }

    @Override
    public void put(String key, byte[] data) {
        try {
            Path p = resolve(key);
            Files.createDirectories(p.getParent());
            Files.write(p, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            Path p = resolve(key);
            return Files.exists(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
