package kr.bojohelp.company;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.bojohelp.file.FileStorage;
import kr.bojohelp.file.ImageSanitizer;
import kr.bojohelp.file.SignedUrls;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 사업자등록증 첨부(PDF 또는 JPG/PNG, 검토 건당 최대 3개). 이미지는 재인코딩(EXIF 제거), PDF 는 헤더(%PDF-)와 크기만 확인하고 그대로 보관하며
 * 내려받을 때는 항상 첨부파일로 내려 준다. 열람은 관리자의 서명 URL(5분)로만 한다.
 */
@Service
public class CompanyLicenseService {

    private static final int MAX_PER_VERIFICATION = 3;
    private static final int MAX_PDF_BYTES = 10 * 1024 * 1024;

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final SignedUrls urls;
    private final CompanyAccess access;
    private final CompanyService companies;

    public CompanyLicenseService(JdbcClient jdbc, FileStorage storage, SignedUrls urls, CompanyAccess access, CompanyService companies) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.urls = urls;
        this.access = access;
        this.companies = companies;
    }

    @Transactional
    public Map<String, Object> upload(long userId, long companyId, byte[] data, String originalName) {
        access.require(userId, companyId, CompanyAccess.MANAGERS);
        long verificationId = companies.latestSubmittedVerification(companyId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "검토 중인 신청이 없어요. 반려된 경우 다시 신청해 주세요."));
        int count = jdbc.sql("SELECT count(*) FROM file_attachments a JOIN files f ON f.id = a.file_id WHERE a.entity_type = 'COMPANY_VERIFICATION' AND a.entity_id = :v AND a.usage = 'BIZ_LICENSE' AND f.status = 'ACTIVE'")
                .param("v", verificationId).query(Integer.class).single();
        if (count >= MAX_PER_VERIFICATION) throw new ResponseStatusException(HttpStatus.CONFLICT, "파일은 3개까지 올릴 수 있어요.");

        byte[] stored;
        String contentType;
        String ext;
        if (isPdf(data)) {
            if (data.length > MAX_PDF_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF 는 10MB 이하로 올려 주세요.");
            stored = data;
            contentType = "application/pdf";
            ext = "pdf";
        } else {
            try {
                stored = ImageSanitizer.sanitize(data, 2400).jpeg();
            } catch (ImageSanitizer.InvalidImageException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF 또는 JPG/PNG 사진으로 올려 주세요.");
            }
            contentType = "image/jpeg";
            ext = "jpg";
        }
        String key = "licenses/" + companyId + "/" + UUID.randomUUID() + "." + ext;
        storage.put(key, stored);
        try {
            long fileId = jdbc.sql("""
                    INSERT INTO files (uploader_user_id, storage_provider, bucket, object_key, original_name, content_type, size_bytes, checksum_sha256, status, is_private)
                    VALUES (:u, :prov, :bucket, :key, :name, :ct, :size, :sha, 'ACTIVE', TRUE) RETURNING id
                    """)
                    .param("u", userId).param("prov", storage.provider()).param("bucket", storage.bucket()).param("key", key).param("name", safeName(originalName))
                    .param("ct", contentType).param("size", stored.length).param("sha", sha256(stored)).query(Long.class).single();
            long attachmentId = jdbc.sql("INSERT INTO file_attachments (file_id, entity_type, entity_id, usage) VALUES (:f, 'COMPANY_VERIFICATION', :v, 'BIZ_LICENSE') RETURNING id")
                    .param("f", fileId).param("v", verificationId).query(Long.class).single();
            return Map.of("id", attachmentId, "contentType", contentType);
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
    }

    /** 관리자 열람용: 검토 건에 붙은 파일 목록(서명 URL 포함). */
    public List<Map<String, Object>> filesOf(long verificationId) {
        return jdbc.sql("SELECT a.id, f.public_id, f.content_type, f.original_name FROM file_attachments a JOIN files f ON f.id = a.file_id WHERE a.entity_type = 'COMPANY_VERIFICATION' AND a.entity_id = :v AND a.usage = 'BIZ_LICENSE' AND f.status = 'ACTIVE' ORDER BY a.id")
                .param("v", verificationId)
                .query((rs, n) -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", rs.getLong(1));
                    o.put("contentType", rs.getString(3));
                    o.put("name", rs.getString(4));
                    o.put("url", urls.urlFor(rs.getObject(2, UUID.class)));
                    return o;
                }).list();
    }

    private static boolean isPdf(byte[] d) {
        return d.length > 5 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F' && d[4] == '-';
    }

    private static String safeName(String name) {
        String n = name == null ? "license" : name.replaceAll("[\\\\/\\r\\n\\u0000]", "_");
        return n.length() > 200 ? n.substring(0, 200) : (n.isBlank() ? "license" : n);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
