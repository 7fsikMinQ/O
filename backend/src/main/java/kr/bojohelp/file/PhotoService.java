package kr.bojohelp.file;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 출연자 프로필 사진(PROFILE_PHOTO 얼굴 / FULL_BODY_PHOTO 전신). 비공개 파일이고 조회는 짧은 수명 서명 URL 로만 한다. */
@Service
public class PhotoService {

    public enum Kind {
        PROFILE("PROFILE_PHOTO"), FULL_BODY("FULL_BODY_PHOTO");

        final String usage;

        Kind(String usage) {
            this.usage = usage;
        }
    }

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final SignedUrls urls;
    private final StorageProperties props;

    public PhotoService(JdbcClient jdbc, FileStorage storage, SignedUrls urls, StorageProperties props) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.urls = urls;
        this.props = props;
    }

    @Transactional
    public Map<String, Object> upload(long userId, Kind kind, byte[] data, String originalName) {
        int count = jdbc.sql("SELECT count(*) FROM file_attachments a JOIN files f ON f.id = a.file_id WHERE a.entity_type = 'ACTOR_PROFILE' AND a.entity_id = :u AND a.usage = :usage AND f.status = 'ACTIVE'")
                .param("u", userId).param("usage", kind.usage).query(Integer.class).single();
        if (count >= props.maxPhotosPerKind()) throw new ResponseStatusException(HttpStatus.CONFLICT);

        ImageSanitizer.Result img = ImageSanitizer.sanitize(data, props.maxImagePixels());
        String key = "photos/" + userId + "/" + UUID.randomUUID() + ".jpg";
        storage.put(key, img.jpeg());
        try {
            var row = jdbc.sql("""
                    INSERT INTO files (uploader_user_id, storage_provider, bucket, object_key, original_name, content_type, size_bytes, checksum_sha256, width, height, status, is_private)
                    VALUES (:u, :prov, :bucket, :key, :name, 'image/jpeg', :size, :sha, :w, :h, 'ACTIVE', TRUE) RETURNING id, public_id
                    """)
                    .param("u", userId).param("prov", storage.provider()).param("bucket", storage.bucket()).param("key", key)
                    .param("name", safeName(originalName)).param("size", img.jpeg().length).param("sha", sha256(img.jpeg()))
                    .param("w", img.width()).param("h", img.height())
                    .query((rs, n) -> new Object[] {rs.getLong("id"), rs.getObject("public_id", UUID.class)}).single();
            long attachmentId = jdbc.sql("INSERT INTO file_attachments (file_id, entity_type, entity_id, usage) VALUES (:f, 'ACTOR_PROFILE', :u, :usage) RETURNING id")
                    .param("f", row[0]).param("u", userId).param("usage", kind.usage).query(Long.class).single();
            return item(attachmentId, kind.name(), (UUID) row[1], img.width(), img.height());
        } catch (RuntimeException e) {
            storage.delete(key);   // DB 실패 시 고아 파일을 남기지 않는다
            throw e;
        }
    }

    public List<Map<String, Object>> list(long userId) {
        return jdbc.sql("""
                SELECT a.id, a.usage, f.public_id, f.width, f.height FROM file_attachments a JOIN files f ON f.id = a.file_id
                 WHERE a.entity_type = 'ACTOR_PROFILE' AND a.entity_id = :u AND f.status = 'ACTIVE'
                 ORDER BY a.id
                """)
                .param("u", userId)
                .query((rs, n) -> item(rs.getLong("id"), "PROFILE_PHOTO".equals(rs.getString("usage")) ? "PROFILE" : "FULL_BODY",
                        rs.getObject("public_id", UUID.class), rs.getInt("width"), rs.getInt("height")))
                .list();
    }

    @Transactional
    public void delete(long userId, long attachmentId) {
        var row = jdbc.sql("""
                SELECT f.id, f.object_key FROM file_attachments a JOIN files f ON f.id = a.file_id
                 WHERE a.id = :a AND a.entity_type = 'ACTOR_PROFILE' AND a.entity_id = :u AND f.uploader_user_id = :u AND f.status = 'ACTIVE'
                """)
                .param("a", attachmentId).param("u", userId)
                .query((rs, n) -> new Object[] {rs.getLong("id"), rs.getString("object_key")}).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));   // 남의 사진은 존재 자체를 숨긴다
        jdbc.sql("DELETE FROM file_attachments WHERE id = :a").param("a", attachmentId).update();
        jdbc.sql("UPDATE files SET status = 'DELETED' WHERE id = :f").param("f", row[0]).update();
        storage.delete((String) row[1]);
    }

    /** 서명 URL 로 요청된 파일의 내용(활성·비공개 파일만). */
    public StoredFile read(UUID publicId) {
        var row = jdbc.sql("SELECT object_key, content_type FROM files WHERE public_id = :p AND status = 'ACTIVE'").param("p", publicId)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        byte[] data = storage.get(row[0]).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new StoredFile(data, row[1]);
    }

    public record StoredFile(byte[] data, String contentType) {}

    private Map<String, Object> item(long id, String kind, UUID publicId, int w, int h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("width", w);
        m.put("height", h);
        m.put("url", urls.urlFor(publicId));
        return m;
    }

    private static String safeName(String name) {
        String n = name == null ? "photo" : name.replaceAll("[\\\\/\\r\\n\\u0000]", "_");
        return n.length() > 200 ? n.substring(0, 200) : (n.isBlank() ? "photo" : n);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
