package kr.bojohelp.application;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kr.bojohelp.file.FileStorage;
import kr.bojohelp.file.ImageSanitizer;
import kr.bojohelp.file.SignedUrls;
import kr.bojohelp.file.StorageProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 지원서에 붙일 "현재 사진"과 "서명" 이미지 업로드. 지원할 때 지원서에 연결된다(연결 전 파일은 본인 것만 쓸 수 있다). 서버가 다시 인코딩해 EXIF 를 지운다. */
@Service
public class ApplyFileService {

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final SignedUrls urls;
    private final StorageProperties props;

    public ApplyFileService(JdbcClient jdbc, FileStorage storage, SignedUrls urls, StorageProperties props) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.urls = urls;
        this.props = props;
    }

    public Map<String, Object> upload(long userId, String kind, byte[] data, String originalName) {
        ImageSanitizer.Result img = ImageSanitizer.sanitize(data, props.maxImagePixels(), "SIGNATURE".equals(kind) ? 60 : 200);
        String key = "apply/" + userId + "/" + kind.toLowerCase() + "-" + UUID.randomUUID() + ".jpg";
        storage.put(key, img.jpeg());
        try {
            var row = jdbc.sql("""
                    INSERT INTO files (uploader_user_id, storage_provider, bucket, object_key, original_name, content_type, size_bytes, checksum_sha256, width, height, status, is_private)
                    VALUES (:u, :prov, :bucket, :key, :name, 'image/jpeg', :size, :sha, :w, :h, 'ACTIVE', TRUE) RETURNING id, public_id
                    """)
                    .param("u", userId).param("prov", storage.provider()).param("bucket", storage.bucket()).param("key", key)
                    .param("name", originalName == null || originalName.isBlank() ? kind.toLowerCase() : originalName.replaceAll("[\\\\/\\r\\n\\u0000]", "_"))
                    .param("size", img.jpeg().length).param("sha", sha256(img.jpeg())).param("w", img.width()).param("h", img.height())
                    .query((rs, n) -> new Object[] {rs.getLong("id"), rs.getObject("public_id", UUID.class)}).single();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileId", row[0]);
            m.put("kind", kind);
            m.put("width", img.width());
            m.put("height", img.height());
            m.put("url", urls.urlFor((UUID) row[1]));
            return m;
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
