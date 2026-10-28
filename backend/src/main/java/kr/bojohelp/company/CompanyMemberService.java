package kr.bojohelp.company;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.bojohelp.audit.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 직원 초대·직책 변경·제외(API-CO04~06).
 * 규칙: 대표(OWNER)는 바꿀 수 없다 / 팀장(MANAGER)은 대표만 지정·변경 / 스케줄러 담당(SCHEDULER)은 업체당 최대 4명 / 초대받은 사람이 수락해야 활성.
 */
@Service
public class CompanyMemberService {

    public record InviteRequest(@NotBlank @Email String email, @NotBlank @Pattern(regexp = "MANAGER|SCHEDULER|FIELD_LEADER|STAFF") String memberRole,
            @Size(max = 50) String departmentName) {}

    public record MemberPatch(@Pattern(regexp = "MANAGER|SCHEDULER|FIELD_LEADER|STAFF") String memberRole, @Pattern(regexp = "REMOVED") String status,
            @Size(max = 50) String departmentName) {}

    private static final int MAX_SCHEDULERS = 4;

    private final JdbcClient jdbc;
    private final CompanyAccess access;
    private final AuditService audit;

    public CompanyMemberService(JdbcClient jdbc, CompanyAccess access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(long userId, long companyId) {
        access.require(userId, companyId, CompanyAccess.MANAGERS);
        return jdbc.sql("SELECT m.user_id, u.name, u.email, m.member_role, m.status, m.department_name FROM company_members m JOIN users u ON u.id = m.user_id WHERE m.company_id = :c AND m.status <> 'REMOVED' ORDER BY m.joined_at, m.user_id")
                .param("c", companyId)
                .query((rs, n) -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("userId", rs.getLong("user_id"));
                    o.put("name", rs.getString("name"));
                    o.put("email", rs.getString("email"));
                    o.put("memberRole", rs.getString("member_role"));
                    o.put("status", rs.getString("status"));
                    o.put("departmentName", rs.getString("department_name"));
                    return o;
                }).list();
    }

    @Transactional
    public void invite(long userId, long companyId, InviteRequest r) {
        CompanyAccess.Membership me = access.require(userId, companyId, CompanyAccess.MANAGERS);
        requireCanAssign(me.role(), r.memberRole());
        Long target = jdbc.sql("SELECT id FROM users WHERE email = :e AND status = 'ACTIVE' AND deleted_at IS NULL").param("e", r.email().trim().toLowerCase()).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "가입한 이메일이 아니에요. 먼저 가입해 달라고 안내해 주세요."));
        var existing = jdbc.sql("SELECT status FROM company_members WHERE company_id = :c AND user_id = :u").param("c", companyId).param("u", target).query(String.class).optional();
        if (existing.isPresent() && !"REMOVED".equals(existing.get())) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 소속되었거나 초대한 사람이에요.");
        checkSchedulerLimit(companyId, r.memberRole(), null);
        if (existing.isPresent()) {
            jdbc.sql("UPDATE company_members SET member_role = :r, department_name = :d, status = 'INVITED', joined_at = now() WHERE company_id = :c AND user_id = :u")
                    .param("r", r.memberRole()).param("d", r.departmentName()).param("c", companyId).param("u", target).update();
        } else {
            jdbc.sql("INSERT INTO company_members (company_id, user_id, member_role, department_name, status) VALUES (:c, :u, :r, :d, 'INVITED')")
                    .param("c", companyId).param("u", target).param("r", r.memberRole()).param("d", r.departmentName()).update();
        }
        audit.record(userId, "COMPANY_MEMBER_INVITE", "COMPANY", companyId, "{\"targetUserId\":" + target + ",\"role\":\"" + r.memberRole() + "\"}");
    }

    @Transactional
    public void patch(long userId, long companyId, long targetUserId, MemberPatch p) {
        CompanyAccess.Membership me = access.require(userId, companyId, CompanyAccess.MANAGERS);
        var target = jdbc.sql("SELECT member_role, status FROM company_members WHERE company_id = :c AND user_id = :u").param("c", companyId).param("u", targetUserId)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("OWNER".equals(target[0])) throw new ResponseStatusException(HttpStatus.FORBIDDEN);          // 대표는 보호
        if ("MANAGER".equals(target[0]) && !"OWNER".equals(me.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);   // 팀장 변경은 대표만
        if (p.memberRole() != null) {
            requireCanAssign(me.role(), p.memberRole());
            checkSchedulerLimit(companyId, p.memberRole(), targetUserId);
        }
        jdbc.sql("""
                UPDATE company_members SET member_role = COALESCE(:r, member_role), status = CASE WHEN CAST(:s AS VARCHAR) = 'REMOVED' THEN 'REMOVED' ELSE status END,
                       department_name = COALESCE(:d, department_name) WHERE company_id = :c AND user_id = :u
                """)
                .param("r", p.memberRole()).param("s", p.status()).param("d", p.departmentName()).param("c", companyId).param("u", targetUserId).update();
        audit.record(userId, "COMPANY_MEMBER_UPDATE", "COMPANY", companyId, "{\"targetUserId\":" + targetUserId + "}");
    }

    public List<Map<String, Object>> myInvitations(long userId) {
        return jdbc.sql("SELECT c.id, c.name, m.member_role FROM company_members m JOIN companies c ON c.id = m.company_id WHERE m.user_id = :u AND m.status = 'INVITED' AND c.deleted_at IS NULL ORDER BY m.joined_at")
                .param("u", userId)
                .query((rs, n) -> Map.<String, Object>of("companyId", rs.getLong(1), "companyName", rs.getString(2), "memberRole", rs.getString(3))).list();
    }

    @Transactional
    public void respond(long userId, long companyId, boolean accept) {
        int changed = jdbc.sql("UPDATE company_members SET status = :s, joined_at = now() WHERE company_id = :c AND user_id = :u AND status = 'INVITED'")
                .param("s", accept ? "ACTIVE" : "REMOVED").param("c", companyId).param("u", userId).update();
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (accept) {
            jdbc.sql("INSERT INTO user_roles (user_id, role_id) SELECT :u, id FROM roles WHERE code = 'COMPANY' ON CONFLICT DO NOTHING").param("u", userId).update();
        }
    }

    private static void requireCanAssign(String actorRole, String newRole) {
        Set<String> assignable = "OWNER".equals(actorRole) ? Set.of("MANAGER", "SCHEDULER", "FIELD_LEADER", "STAFF") : Set.of("SCHEDULER", "FIELD_LEADER", "STAFF");
        if (!assignable.contains(newRole)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private void checkSchedulerLimit(long companyId, String newRole, Long excludeUserId) {
        if (!"SCHEDULER".equals(newRole)) return;
        // 동시에 두 명을 지정해 4명 제한을 넘기지 않도록 업체 단위로 트랜잭션 잠금
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:k))").param("k", "company-scheduler:" + companyId).query().singleRow();
        int n = jdbc.sql("SELECT count(*) FROM company_members WHERE company_id = :c AND member_role = 'SCHEDULER' AND status IN ('ACTIVE','INVITED') AND (CAST(:x AS BIGINT) IS NULL OR user_id <> :x)")
                .param("c", companyId).param("x", excludeUserId).query(Integer.class).single();
        if (n >= MAX_SCHEDULERS) throw new ResponseStatusException(HttpStatus.CONFLICT, "스케줄러 담당은 업체당 최대 4명이에요.");
    }
}
