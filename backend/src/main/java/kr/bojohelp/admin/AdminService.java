package kr.bojohelp.admin;

import java.util.List;
import java.util.Map;
import kr.bojohelp.audit.AuditService;
import kr.bojohelp.common.Rows;
import kr.bojohelp.auth.PhoneNumber;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 관리자 기능: 회원 검색·정지·해제, 공고 숨김, 신고 처리, 운영 설정·최저시급·기능 스위치, 감사 로그, 통계. 모든 변경은 감사 기록을 남긴다. */
@Service
public class AdminService {

    private final JdbcClient jdbc;
    private final AuditService audit;

    public AdminService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<Map<String, Object>> users(String q, String status) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT u.id, u.name, u.email, u.phone, u.status, u.created_at, u.last_login_at,
                       (SELECT string_agg(r.code, ',' ORDER BY r.code) FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = u.id) AS roles
                  FROM users u WHERE u.deleted_at IS NULL AND (CAST(:s AS text) IS NULL OR u.status = :s)
                   AND (CAST(:q AS text) IS NULL OR u.name ILIKE :q OR u.email ILIKE :q OR u.phone LIKE :q) ORDER BY u.id DESC LIMIT 100
                """).param("q", like).param("s", status).query(Rows.camel()).list();
        for (Map<String, Object> r : rows) if (r.get("phone") != null) r.put("phone", PhoneNumber.mask((String) r.get("phone")));   // 관리자 목록에서도 번호는 마스킹
        return rows;
    }

    @Transactional
    public void setUserStatus(long adminId, long userId, boolean suspend, String reason) {
        if (suspend && (reason == null || reason.isBlank())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정지 사유를 적어 주세요.");
        boolean admin = jdbc.sql("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = :u AND r.code IN ('ADMIN','SUPER_ADMIN')").param("u", userId).query(Integer.class).single() > 0;
        if (admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 계정은 여기서 정지할 수 없어요.");
        int n = jdbc.sql("UPDATE users SET status = :s, updated_at = now() WHERE id = :id AND deleted_at IS NULL AND status IN ('ACTIVE','SUSPENDED')").param("s", suspend ? "SUSPENDED" : "ACTIVE").param("id", userId).update();
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (suspend) jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", userId).update();
        audit.record(adminId, suspend ? "USER_SUSPEND" : "USER_RESTORE", "USER", userId, suspend ? kr.bojohelp.common.Json.write(Map.of("reason", reason)) : null);
    }

    public List<Map<String, Object>> jobs(String status) {
        return jdbc.sql("""
                SELECT p.id, p.title, p.status, c.name AS company_name, p.first_shoot_date, p.published_at, p.deleted_at IS NOT NULL AS hidden
                  FROM job_postings p JOIN companies c ON c.id = p.company_id WHERE (CAST(:s AS text) IS NULL OR p.status = :s) ORDER BY p.id DESC LIMIT 100
                """).param("s", status).query(Rows.camel()).list();
    }

    @Transactional
    public void hideJob(long adminId, long postingId, String reason) {
        int n = jdbc.sql("UPDATE job_postings SET deleted_at = now(), status = CASE WHEN status = 'OPEN' THEN 'CLOSED' ELSE status END, updated_at = now() WHERE id = :id AND deleted_at IS NULL").param("id", postingId).update();
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        audit.record(adminId, "JOB_HIDE", "JOB_POSTING", postingId, kr.bojohelp.common.Json.write(Map.of("reason", reason == null ? "" : reason)));
    }

    public List<Map<String, Object>> reports(String status) {
        return jdbc.sql("SELECT id, reporter_id, target_type, target_id, reason_code, detail, status, created_at FROM reports WHERE (CAST(:s AS text) IS NULL OR status = :s) ORDER BY id DESC LIMIT 100").param("s", status).query(Rows.camel()).list();
    }

    @Transactional
    public void resolveReport(long adminId, long id, String status) {
        if (!List.of("IN_REVIEW", "RESOLVED", "DISMISSED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        int n = jdbc.sql("UPDATE reports SET status = :s, handled_by = :a, handled_at = now() WHERE id = :id").param("s", status).param("a", adminId).param("id", id).update();
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        audit.record(adminId, "REPORT_" + status, "REPORT", id, null);
    }

    public List<Map<String, Object>> settings() {
        return jdbc.sql("SELECT key, value, description, updated_at FROM app_settings ORDER BY key").query(Rows.camel()).list();
    }

    @Transactional
    public void putSetting(long adminId, String key, String value) {
        if (value == null || value.length() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        int n = jdbc.sql("UPDATE app_settings SET value = :v, updated_at = now() WHERE key = :k").param("v", value).param("k", key).update();
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        audit.record(adminId, "SETTING_CHANGE", "APP_SETTING", null, kr.bojohelp.common.Json.write(Map.of("key", key)));
    }

    @Transactional
    public void putMinimumWage(long adminId, int year, int hourly) {
        if (year < 2000 || year > 2100 || hourly < 1000 || hourly > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        jdbc.sql("INSERT INTO minimum_wages (year, hourly_krw, effective_from, source_note, created_by) VALUES (:y, :h, make_date(:y, 1, 1), '관리자 등록', :a) ON CONFLICT (year) DO UPDATE SET hourly_krw = EXCLUDED.hourly_krw, created_by = EXCLUDED.created_by")
                .param("y", year).param("h", hourly).param("a", adminId).update();
        audit.record(adminId, "MIN_WAGE_CHANGE", "MINIMUM_WAGE", (long) year, "{\"hourly\":" + hourly + "}");
    }

    public List<Map<String, Object>> flags() {
        return jdbc.sql("SELECT code, enabled, description, needs_legal_review, updated_at FROM feature_flags ORDER BY code").query(Rows.camel()).list();
    }

    /** 법무 확인이 필요한 스위치를 켤 때는 confirmLegal=true 를 함께 보내야 한다(17번 §4). */
    @Transactional
    public void putFlag(long adminId, String code, boolean enabled, boolean confirmLegal) {
        Boolean needs = jdbc.sql("SELECT needs_legal_review FROM feature_flags WHERE code = :c").param("c", code).query(Boolean.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("PAYOUT_EXECUTION".equals(code) && enabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "송금 실행은 구현하지 않는 기능이에요.");
        if (enabled && Boolean.TRUE.equals(needs) && !confirmLegal) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "법무 확인 후 켜는 기능이에요. confirmLegal 을 함께 보내 주세요.");
        jdbc.sql("UPDATE feature_flags SET enabled = :e, updated_by = :a, updated_at = now() WHERE code = :c").param("e", enabled).param("a", adminId).param("c", code).update();
        audit.record(adminId, "FEATURE_FLAG", "FEATURE_FLAG", null, kr.bojohelp.common.Json.write(Map.of("code", code, "enabled", enabled)));
    }

    public List<Map<String, Object>> auditLogs(String action, Long before, int limit) {
        return jdbc.sql("""
                SELECT id, actor_user_id, action, entity_type, entity_id, created_at FROM audit_logs
                 WHERE (CAST(:a AS text) IS NULL OR action = :a) AND (CAST(:b AS bigint) IS NULL OR id < :b) ORDER BY id DESC LIMIT :n
                """).param("a", action).param("b", before).param("n", Math.min(Math.max(limit, 1), 200)).query(Rows.camel()).list();
    }

    public Map<String, Object> stats() {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM users WHERE deleted_at IS NULL) AS users,
                       (SELECT count(*) FROM actor_profiles) AS actors,
                       (SELECT count(*) FROM companies WHERE status = 'APPROVED') AS companies,
                       (SELECT count(*) FROM companies WHERE status = 'PENDING') AS pending_companies,
                       (SELECT count(*) FROM job_postings WHERE status = 'OPEN' AND deleted_at IS NULL) AS open_postings,
                       (SELECT count(*) FROM applications) AS applications,
                       (SELECT count(*) FROM applications WHERE status = 'CONFIRMED') AS confirmed,
                       (SELECT count(*) FROM reports WHERE status = 'RECEIVED') AS open_reports
                """).query(Rows.camel()).single();
    }
}
