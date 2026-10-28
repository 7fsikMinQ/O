package kr.bojohelp.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.bojohelp.audit.AuditService;
import kr.bojohelp.common.Rows;
import kr.bojohelp.crypto.FieldCipher;
import kr.bojohelp.file.SignedUrls;
import kr.bojohelp.profile.RrnPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 업체가 보는 지원자 정보. 휴대폰 번호는 지원 단계부터, 집주소·계좌는 합격 통보(SELECTED) 이후에만, 주민번호는 항상 마스킹이며
 * 전체 열람은 대표·팀장이 사유를 적고 감사 기록을 남길 때만 가능하다(CLAUDE.md §2-5).
 */
@Service
public class ApplicantQuery {

    private final JdbcClient jdbc;
    private final SignedUrls urls;
    private final FieldCipher cipher;
    private final AuditService audit;

    public ApplicantQuery(JdbcClient jdbc, SignedUrls urls, FieldCipher cipher, AuditService audit) {
        this.jdbc = jdbc;
        this.urls = urls;
        this.cipher = cipher;
        this.audit = audit;
    }

    public List<Map<String, Object>> forPosition(long positionId, String status) {
        return list("a.position_id = :id", positionId, status);
    }

    public List<Map<String, Object>> forPosting(long postingId, String status) {
        return list("a.posting_id = :id", postingId, status);
    }

    private List<Map<String, Object>> list(String where, long id, String status) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT a.id, a.status, a.applied_at, a.position_id, pos.title AS position_title, a.schedule_id, a.actor_user_id, a.applicant_name, a.applicant_phone,
                       a.profile_snapshot ->> 'gender' AS gender, a.profile_snapshot ->> 'birth_date' AS birth_date,
                       (a.profile_snapshot ->> 'height_cm')::int AS height_cm, (a.profile_snapshot ->> 'weight_kg')::int AS weight_kg,
                       a.response_due_at, a.departure_confirmed_at, a.conflict_override, a.tax_identity_id IS NOT NULL AS has_rrn,
                       ap.confirmed_count, ap.attended_count, ap.noshow_count, ap.penalty_count,
                       (SELECT count(*) FROM applications x JOIN job_postings xp ON xp.id = x.posting_id JOIN job_postings cp ON cp.id = a.posting_id
                         WHERE x.actor_user_id = a.actor_user_id AND x.status = 'CONFIRMED' AND xp.company_id = cp.company_id) AS with_us_count,
                       (SELECT count(*) FROM company_starred_actors st JOIN job_postings cp ON cp.id = a.posting_id WHERE st.company_id = cp.company_id AND st.actor_user_id = a.actor_user_id) > 0 AS starred,
                       (SELECT f.public_id FROM file_attachments fa JOIN files f ON f.id = fa.file_id WHERE fa.entity_type = 'APPLICATION' AND fa.entity_id = a.id AND fa.usage = 'APPLY_PHOTO' LIMIT 1) AS photo_public_id
                  FROM applications a JOIN job_positions pos ON pos.id = a.position_id JOIN actor_profiles ap ON ap.user_id = a.actor_user_id
                 WHERE %s AND (CAST(:s AS text) IS NULL OR a.status = :s) ORDER BY a.applied_at, a.id LIMIT 500
                """.formatted(where)).param("id", id).param("s", status).query(Rows.camel()).list();
        for (Map<String, Object> r : rows) {
            Object pid = r.remove("photoPublicId");
            r.put("photoUrl", pid == null ? null : urls.urlFor((UUID) pid));
        }
        return rows;
    }

    public Map<String, Object> detail(long applicationId, long companyId) {
        Map<String, Object> a = jdbc.sql("""
                SELECT a.id, a.status, a.applied_at, a.position_id, pos.title AS position_title, a.posting_id, p.title AS posting_title, a.actor_user_id, a.applicant_name, a.applicant_phone,
                       a.departure_location, a.profile_snapshot, a.bank_name, a.account_holder, a.account_number_enc, a.message, a.signed_at, a.signed_terms_version, a.snapshot_hash,
                       a.response_due_at, a.confirmed_at, a.departure_confirmed_at, a.conflict_override, a.tax_identity_id, a.work_start_at, a.work_end_at,
                       ap.confirmed_count, ap.attended_count, ap.noshow_count, ap.penalty_count, p.company_id,
                       (SELECT count(*) FROM company_starred_actors st WHERE st.company_id = p.company_id AND st.actor_user_id = a.actor_user_id) > 0 AS starred
                  FROM applications a JOIN job_positions pos ON pos.id = a.position_id JOIN job_postings p ON p.id = a.posting_id JOIN actor_profiles ap ON ap.user_id = a.actor_user_id
                 WHERE a.id = :id
                """).param("id", applicationId).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (((Number) a.get("companyId")).longValue() != companyId) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        boolean revealed = List.of("SELECTED", "CONFIRMED").contains(a.get("status")) || a.get("confirmedAt") != null;
        Map<String, Object> snap = (Map<String, Object>) a.get("profileSnapshot");
        if (!revealed && snap != null) {   // 합격 통보 전에는 집주소를 가린다
            snap.remove("home_address");
            snap.remove("home_address_detail");
            snap.remove("home_postal_code");
        }
        String enc = (String) a.remove("accountNumberEnc");
        if (revealed && enc != null) {
            String n = cipher.decrypt(enc);
            a.put("accountMasked", "****" + n.substring(Math.max(0, n.length() - 4)));
        } else {
            a.put("bankName", null);
            a.put("accountHolder", null);
        }
        Object taxId = a.remove("taxIdentityId");
        a.put("rrnMasked", taxId == null ? null : jdbc.sql("SELECT rrn_enc FROM actor_tax_identities WHERE id = :id").param("id", taxId).query(String.class).optional().map(e -> RrnPolicy.mask(cipher.decrypt(e))).orElse(null));
        a.put("files", jdbc.sql("""
                SELECT fa.usage, f.public_id FROM file_attachments fa JOIN files f ON f.id = fa.file_id WHERE fa.entity_type = 'APPLICATION' AND fa.entity_id = :id AND f.status = 'ACTIVE'
                """).param("id", applicationId).query((rs, n) -> Map.<String, Object>of("usage", rs.getString(1), "url", urls.urlFor(rs.getObject(2, UUID.class)))).list());
        a.put("history", jdbc.sql("SELECT from_status, to_status, reason, created_at FROM application_status_histories WHERE application_id = :id ORDER BY id").param("id", applicationId).query(Rows.camel()).list());
        return a;
    }

    /** 이 업체에서의 그 출연자 이력 + 공개되는 활동 횟수(점수 없음). */
    public Map<String, Object> actorHistory(long actorUserId, long companyId) {
        Map<String, Object> counts = jdbc.sql("SELECT confirmed_count, attended_count, noshow_count, penalty_count FROM actor_profiles WHERE user_id = :u").param("u", actorUserId)
                .query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        counts.put("withUs", jdbc.sql("""
                SELECT a.id, a.status, a.work_start_at, p.title AS posting_title, pos.title AS position_title
                  FROM applications a JOIN job_postings p ON p.id = a.posting_id JOIN job_positions pos ON pos.id = a.position_id
                 WHERE a.actor_user_id = :u AND p.company_id = :c ORDER BY a.work_start_at DESC LIMIT 100
                """).param("u", actorUserId).param("c", companyId).query(Rows.camel()).list());
        return counts;
    }

    /** 지원자의 다른 공고 지원 목록(무료 운영 중 허용: free_open_application_history). 업체 이름 대신 일정만 보여 준다. */
    public List<Map<String, Object>> otherApplications(long actorUserId, long companyId) {
        boolean open = "true".equals(jdbc.sql("SELECT value FROM app_settings WHERE key = 'free_open_application_history'").query(String.class).optional().orElse("true"));
        if (!open) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "구독 후에 볼 수 있어요.");
        return jdbc.sql("""
                SELECT a.status, a.work_start_at, a.work_end_at, (p.company_id = :c) AS ours
                  FROM applications a JOIN job_postings p ON p.id = a.posting_id
                 WHERE a.actor_user_id = :u AND a.status IN ('APPLIED','SELECTED','CONFIRMED') ORDER BY a.work_start_at LIMIT 100
                """).param("u", actorUserId).param("c", companyId).query(Rows.camel()).list();
    }

    /** 마감 후 추가 합격 후보: 자동 불합격 또는 대기 중인 지원자 중 일정 ±N일에 다른 합격이 없는 사람. */
    public List<Map<String, Object>> reserveCandidates(long postingId) {
        int window = Integer.parseInt(jdbc.sql("SELECT value FROM app_settings WHERE key = 'reserve_candidate_window_days'").query(String.class).optional().orElse("1"));
        return jdbc.sql("""
                SELECT a.id, a.status, a.position_id, pos.title AS position_title, a.applicant_name, a.applicant_phone,
                       (pos.headcount - pos.selected_count - pos.confirmed_count) AS remaining
                  FROM applications a JOIN job_positions pos ON pos.id = a.position_id
                 WHERE a.posting_id = :p AND a.status IN ('AUTO_REJECTED','APPLIED') AND pos.headcount - pos.selected_count - pos.confirmed_count > 0
                   AND NOT EXISTS (
                     SELECT 1 FROM applications o WHERE o.actor_user_id = a.actor_user_id AND o.id <> a.id AND o.status IN ('SELECTED','CONFIRMED')
                       AND (o.work_start_at AT TIME ZONE 'Asia/Seoul')::date <= ((a.work_end_at AT TIME ZONE 'Asia/Seoul')::date + :w)
                       AND ((o.work_end_at AT TIME ZONE 'Asia/Seoul') - interval '1 second')::date >= ((a.work_start_at AT TIME ZONE 'Asia/Seoul')::date - :w))
                 ORDER BY a.applied_at, a.id LIMIT 200
                """).param("p", postingId).param("w", window).query(Rows.camel()).list();
    }

    /** 주민번호 전체 열람(대표·팀장, 사유 필수). 응답 외에는 어디에도 평문을 남기지 않고 감사 기록(누가·어떤 지원서·사유)만 남긴다. */
    @Transactional
    public Map<String, Object> revealRrn(long userId, long companyId, long applicationId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "열람 사유를 적어 주세요.");
        var row = jdbc.sql("""
                SELECT t.rrn_enc, p.company_id FROM applications a JOIN job_postings p ON p.id = a.posting_id LEFT JOIN actor_tax_identities t ON t.id = a.tax_identity_id WHERE a.id = :id
                """).param("id", applicationId).query((rs, n) -> new Object[] {rs.getString(1), rs.getLong(2)}).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ((Long) row[1] != companyId || row[0] == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String digits = cipher.decrypt((String) row[0]);
        audit.record(userId, "RRN_REVEAL", "APPLICATION", applicationId, kr.bojohelp.common.Json.write(Map.of("reason", reason)));
        return Map.of("rrn", digits.substring(0, 6) + "-" + digits.substring(6));
    }
}
