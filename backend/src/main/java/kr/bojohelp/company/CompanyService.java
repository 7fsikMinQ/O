package kr.bojohelp.company;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.bojohelp.audit.AuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 업체 등록·재신청·프로필(API-CO01~03). 등록하면 PENDING 이고 관리자 승인 후에만 공고를 올릴 수 있다. */
@Service
public class CompanyService {

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank String bizRegNo,
            @NotBlank @Size(max = 50) String representative,
            @Pattern(regexp = "[0-9+\\- ]{7,20}") String phone,
            @Size(max = 200) String address,
            Long regionId,
            @Size(max = 2000) String introduction) {}

    public record ProfilePatch(
            @Pattern(regexp = "[0-9+\\- ]{7,20}") String phone,
            @Size(max = 200) String address,
            Long regionId,
            @Size(max = 2000) String introduction) {}

    private static final int MAX_OWNED = 5;

    private final JdbcClient jdbc;
    private final CompanyAccess access;
    private final AuditService audit;

    public CompanyService(JdbcClient jdbc, CompanyAccess access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> register(long userId, RegisterRequest r) {
        String biz;
        try {
            biz = BizRegNo.normalize(r.bizRegNo());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사업자등록번호를 다시 확인해 주세요.");
        }
        int owned = jdbc.sql("SELECT count(*) FROM company_members WHERE user_id = :u AND member_role = 'OWNER' AND status = 'ACTIVE'").param("u", userId).query(Integer.class).single();
        if (owned >= MAX_OWNED) throw new ResponseStatusException(HttpStatus.CONFLICT, "등록할 수 있는 업체 수를 넘었어요.");

        long companyId;
        try {
            companyId = jdbc.sql("INSERT INTO companies (name, biz_reg_no, representative, phone, address, region_id, introduction) VALUES (:n, :b, :rep, :p, :a, :region, :i) RETURNING id")
                    .param("n", r.name().trim()).param("b", biz).param("rep", r.representative().trim()).param("p", r.phone()).param("a", r.address())
                    .param("region", r.regionId()).param("i", r.introduction()).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 사업자등록번호예요.");
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);   // 존재하지 않는 지역 등
        }
        jdbc.sql("INSERT INTO company_members (company_id, user_id, member_role, status) VALUES (:c, :u, 'OWNER', 'ACTIVE')").param("c", companyId).param("u", userId).update();
        long verificationId = jdbc.sql("INSERT INTO company_verifications (company_id) VALUES (:c) RETURNING id").param("c", companyId).query(Long.class).single();
        grantCompanyRole(userId);
        audit.record(userId, "COMPANY_REGISTER", "COMPANY", companyId, null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", companyId);
        m.put("status", "PENDING");
        m.put("verificationId", verificationId);
        return m;
    }

    /** 반려된 업체를 고쳐서 다시 신청: PENDING 으로 돌리고 새 검토 건을 만든다(OWNER 만). */
    @Transactional
    public Map<String, Object> resubmit(long userId, long companyId) {
        CompanyAccess.Membership m = access.require(userId, companyId, java.util.Set.of("OWNER"));
        if (!"REJECTED".equals(m.companyStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        jdbc.sql("UPDATE companies SET status = 'PENDING', updated_at = now() WHERE id = :c").param("c", companyId).update();
        long verificationId = jdbc.sql("INSERT INTO company_verifications (company_id) VALUES (:c) RETURNING id").param("c", companyId).query(Long.class).single();
        audit.record(userId, "COMPANY_RESUBMIT", "COMPANY", companyId, null);
        return Map.of("id", companyId, "status", "PENDING", "verificationId", verificationId);
    }

    public List<Map<String, Object>> mine(long userId) {
        return jdbc.sql("""
                SELECT c.id, c.name, c.status, m.member_role, m.status AS member_status FROM company_members m JOIN companies c ON c.id = m.company_id
                 WHERE m.user_id = :u AND m.status IN ('ACTIVE','INVITED') AND c.deleted_at IS NULL ORDER BY c.id
                """)
                .param("u", userId)
                .query((rs, n) -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("companyId", rs.getLong("id"));
                    o.put("name", rs.getString("name"));
                    o.put("status", rs.getString("status"));
                    o.put("memberRole", rs.getString("member_role"));
                    o.put("memberStatus", rs.getString("member_status"));
                    return o;
                }).list();
    }

    /** 승인된 업체는 로그인한 누구나, 그 외에는 구성원만 본다(사업자번호는 구성원에게만). */
    public Map<String, Object> get(long userId, long companyId) {
        Map<String, Object> c = jdbc.sql("SELECT id, name, representative, phone, address, region_id, introduction, status, biz_reg_no FROM companies WHERE id = :c AND deleted_at IS NULL")
                .param("c", companyId)
                .query((rs, n) -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", rs.getLong("id"));
                    o.put("name", rs.getString("name"));
                    o.put("representative", rs.getString("representative"));
                    o.put("phone", rs.getString("phone"));
                    o.put("address", rs.getString("address"));
                    o.put("regionId", rs.getObject("region_id"));
                    o.put("introduction", rs.getString("introduction"));
                    o.put("status", rs.getString("status"));
                    o.put("_biz", rs.getString("biz_reg_no"));
                    return o;
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean member = isActiveMember(userId, companyId);
        if (!"APPROVED".equals(c.get("status")) && !member) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String biz = (String) c.remove("_biz");
        if (member) c.put("bizRegNo", biz);
        return c;
    }

    @Transactional
    public void patch(long userId, long companyId, ProfilePatch p) {
        access.require(userId, companyId, CompanyAccess.MANAGERS);
        try {
            jdbc.sql("""
                    UPDATE companies SET phone = COALESCE(:p, phone), address = COALESCE(:a, address), region_id = COALESCE(:r, region_id),
                           introduction = COALESCE(:i, introduction), updated_at = now() WHERE id = :c
                    """)
                    .param("p", p.phone()).param("a", p.address()).param("r", p.regionId()).param("i", p.introduction()).param("c", companyId).update();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private boolean isActiveMember(long userId, long companyId) {
        return jdbc.sql("SELECT count(*) FROM company_members WHERE company_id = :c AND user_id = :u AND status = 'ACTIVE'").param("c", companyId).param("u", userId).query(Integer.class).single() > 0;
    }

    private void grantCompanyRole(long userId) {
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) SELECT :u, id FROM roles WHERE code = 'COMPANY' ON CONFLICT DO NOTHING").param("u", userId).update();
    }

    /** 같은 패키지의 다른 서비스가 최신 검토 건 id 를 찾는 데 쓴다. */
    Optional<Long> latestSubmittedVerification(long companyId) {
        return jdbc.sql("SELECT id FROM company_verifications WHERE company_id = :c AND status = 'SUBMITTED' ORDER BY id DESC LIMIT 1").param("c", companyId).query(Long.class).optional();
    }
}
