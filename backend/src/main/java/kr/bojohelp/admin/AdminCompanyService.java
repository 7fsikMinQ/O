package kr.bojohelp.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.bojohelp.audit.AuditService;
import kr.bojohelp.company.CompanyLicenseService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 관리자의 업체 승인·반려(API-AD01~03). 결정은 감사 로그와 아웃박스(알림용)에 같은 트랜잭션으로 남긴다. 반려는 사유 필수. */
@Service
public class AdminCompanyService {

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final CompanyLicenseService licenses;

    public AdminCompanyService(JdbcClient jdbc, AuditService audit, CompanyLicenseService licenses) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.licenses = licenses;
    }

    public List<Map<String, Object>> list(String status) {
        String s = status == null || status.isBlank() ? "PENDING" : status;
        if (!List.of("PENDING", "APPROVED", "REJECTED", "SUSPENDED").contains(s)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return jdbc.sql("""
                SELECT c.id, c.name, c.biz_reg_no, c.representative, c.status, c.created_at, u.name AS owner_name
                  FROM companies c LEFT JOIN company_members m ON m.company_id = c.id AND m.member_role = 'OWNER' AND m.status = 'ACTIVE' LEFT JOIN users u ON u.id = m.user_id
                 WHERE c.status = :s AND c.deleted_at IS NULL ORDER BY c.created_at
                """)
                .param("s", s)
                .query((rs, n) -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", rs.getLong("id"));
                    o.put("name", rs.getString("name"));
                    o.put("bizRegNo", rs.getString("biz_reg_no"));
                    o.put("representative", rs.getString("representative"));
                    o.put("ownerName", rs.getString("owner_name"));
                    o.put("status", rs.getString("status"));
                    o.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return o;
                }).list();
    }

    /** 상세 + 사업자등록증 파일(서명 URL). 열람은 감사 기록에 남긴다. */
    @Transactional
    public Map<String, Object> detail(long adminId, long companyId) {
        var row = jdbc.sql("SELECT id, name, biz_reg_no, representative, phone, address, introduction, status FROM companies WHERE id = :c AND deleted_at IS NULL").param("c", companyId)
                .query((rs, n) -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", rs.getLong("id"));
                    o.put("name", rs.getString("name"));
                    o.put("bizRegNo", rs.getString("biz_reg_no"));
                    o.put("representative", rs.getString("representative"));
                    o.put("phone", rs.getString("phone"));
                    o.put("address", rs.getString("address"));
                    o.put("introduction", rs.getString("introduction"));
                    o.put("status", rs.getString("status"));
                    return o;
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Long verificationId = jdbc.sql("SELECT id FROM company_verifications WHERE company_id = :c ORDER BY id DESC LIMIT 1").param("c", companyId).query(Long.class).optional().orElse(null);
        row.put("licenses", verificationId == null ? List.of() : licenses.filesOf(verificationId));
        audit.record(adminId, "COMPANY_LICENSE_VIEW", "COMPANY", companyId, null);
        return row;
    }

    @Transactional
    public void approve(long adminId, long companyId) {
        decide(adminId, companyId, "APPROVED", null);
    }

    @Transactional
    public void reject(long adminId, long companyId, String reason) {
        decide(adminId, companyId, "REJECTED", reason.trim());
    }

    private void decide(long adminId, long companyId, String result, String reason) {
        int changed = jdbc.sql("""
                UPDATE companies SET status = :s, approved_by = CASE WHEN :s = 'APPROVED' THEN :a ELSE NULL END,
                       approved_at = CASE WHEN :s = 'APPROVED' THEN now() ELSE NULL END, updated_at = now()
                 WHERE id = :c AND status = 'PENDING' AND deleted_at IS NULL
                """)
                .param("s", result).param("a", adminId).param("c", companyId).update();
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "대기 중인 업체만 처리할 수 있어요.");
        jdbc.sql("UPDATE company_verifications SET status = :s, reject_reason = :r, reviewed_by = :a, reviewed_at = now() WHERE company_id = :c AND status = 'SUBMITTED'")
                .param("s", result).param("r", reason).param("a", adminId).param("c", companyId).update();
        audit.record(adminId, "APPROVED".equals(result) ? "COMPANY_APPROVE" : "COMPANY_REJECT", "COMPANY", companyId, reason == null ? null : "{\"reasonGiven\":true}");
        audit.publish("COMPANY", companyId, "APPROVED".equals(result) ? "COMPANY_APPROVED" : "COMPANY_REJECTED", "{\"companyId\":" + companyId + "}");
    }
}
