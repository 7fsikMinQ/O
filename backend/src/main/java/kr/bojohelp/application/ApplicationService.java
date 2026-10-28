package kr.bojohelp.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.bojohelp.audit.AuditService;
import kr.bojohelp.common.Json;
import kr.bojohelp.common.Rows;
import kr.bojohelp.crypto.FieldCipher;
import kr.bojohelp.file.SignedUrls;
import kr.bojohelp.message.NotificationService;
import kr.bojohelp.profile.BankCatalog;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 지원(REQ-PC): 현재 사진 + 전자서명 + 주민번호(첫 지원 전) + 계좌. 지원서는 증빙이므로 지원 당시 프로필·계좌·주민번호 버전을 스냅샷으로 남기고
 * 정규화 JSON + 서명 파일 해시를 SHA-256 으로 묶어 사후 변조를 알아챌 수 있게 한다.
 */
@Service
public class ApplicationService {

    public record AccountReq(@NotBlank String bankCode, @NotBlank String accountNumber, @NotBlank @Size(max = 50) String holder) {}

    public record ApplyReq(
            @NotNull Long photoFileId,
            @NotNull Long signatureFileId,
            Boolean agreeESignature,
            @Size(max = 1000) String message,
            @Size(max = 200) String departureLocation,
            Boolean useProfileAccount,
            @Valid AccountReq account) {}

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcClient jdbc;
    private final FieldCipher cipher;
    private final NotificationService notifications;
    private final AuditService audit;
    private final SignedUrls urls;

    public ApplicationService(JdbcClient jdbc, FieldCipher cipher, NotificationService notifications, AuditService audit, SignedUrls urls) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.notifications = notifications;
        this.audit = audit;
        this.urls = urls;
    }

    // ===== 지원 폼 사전 정보 =====

    public Map<String, Object> applyForm(long userId, long positionId) {
        Map<String, Object> pos = positionForApply(positionId, false);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> profile = profile(userId);
        List<String> missing = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (profile == null) {
            missing.add("PROFILE");
        } else {
            reasons.addAll(Eligibility.check(profile, pos, LocalDate.now(KST), minorApplyEnabled()));
            if (phoneOf(userId) == null) missing.add("PHONE");
            if (requireRrn() && !hasRrn(userId)) missing.add("RRN");
            out.put("profile", summary(profile));
            out.put("hasPayoutAccount", jdbc.sql("SELECT count(*) FROM actor_payout_accounts WHERE user_id = :u").param("u", userId).query(Integer.class).single() > 0);
        }
        out.put("missing", missing);
        out.put("reasons", reasons);
        out.put("eligible", missing.isEmpty() && reasons.isEmpty() && profile != null);
        out.put("position", Map.of("id", pos.get("id"), "title", pos.get("title"), "workStartAt", pos.get("workStartAt"), "workEndAt", pos.get("workEndAt")));
        out.put("eSignatureTerm", pendingESignatureTerm(userId));
        out.put("conflicts", overlapping(userId, (OffsetDateTime) pos.get("workStartAtValue"), (OffsetDateTime) pos.get("workEndAtValue"), 0));
        out.put("alreadyApplied", jdbc.sql("SELECT count(*) FROM applications WHERE position_id = :p AND actor_user_id = :u AND status IN ('APPLIED','SELECTED','CONFIRMED')")
                .param("p", positionId).param("u", userId).query(Integer.class).single() > 0);
        return out;
    }

    // ===== 지원 =====

    @Transactional
    public Map<String, Object> apply(long userId, long positionId, ApplyReq r, String ip, String userAgent) {
        Map<String, Object> pos = positionForApply(positionId, true);
        long companyId = ((Number) pos.get("companyId")).longValue();
        Map<String, Object> profile = profile(userId);
        if (profile == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "프로필을 먼저 저장해 주세요.");
        String phone = phoneOf(userId);
        if (phone == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "연락처(휴대폰 번호)를 프로필에 먼저 입력해 주세요.");
        List<String> reasons = Eligibility.check(profile, pos, LocalDate.now(KST), minorApplyEnabled());
        if (!reasons.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", reasons));
        Long taxId = jdbc.sql("SELECT id FROM actor_tax_identities WHERE user_id = :u AND is_active").param("u", userId).query(Long.class).optional().orElse(null);
        if (requireRrn() && taxId == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "첫 지원 전에 주민등록번호를 등록해 주세요.");
        if (jdbc.sql("SELECT count(*) FROM company_actor_blocks WHERE company_id = :c AND actor_user_id = :u").param("c", companyId).param("u", userId).query(Integer.class).single() > 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 공고에는 지원할 수 없어요.");
        }

        // 전자서명 약관 동의(최신 버전에 아직 동의하지 않았다면 이번에 받는다)
        Map<String, Object> term = pendingESignatureTerm(userId);
        String termVersion;
        if (term != null) {
            if (!Boolean.TRUE.equals(r.agreeESignature())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "전자서명 및 증빙 보관에 동의해 주세요.");
            jdbc.sql("INSERT INTO user_consents (user_id, term_id, agreed) VALUES (:u, :t, TRUE) ON CONFLICT (user_id, term_id) DO NOTHING").param("u", userId).param("t", term.get("id")).update();
            termVersion = (String) term.get("version");
        } else {
            termVersion = jdbc.sql("SELECT version FROM terms WHERE type = 'E_SIGNATURE' AND effective_at <= now() ORDER BY effective_at DESC LIMIT 1").query(String.class).optional().orElse("0");
        }

        String sigSha = ownFreeFile(userId, r.signatureFileId());
        ownFreeFile(userId, r.photoFileId());

        String bankCode, bankName, accountEnc, holder;
        if (Boolean.TRUE.equals(r.useProfileAccount()) || r.account() == null) {
            var acc = jdbc.sql("SELECT bank_code, bank_name, account_number_enc, account_holder FROM actor_payout_accounts WHERE user_id = :u").param("u", userId)
                    .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "정산 계좌를 입력해 주세요."));
            bankCode = acc[0]; bankName = acc[1]; accountEnc = acc[2]; holder = acc[3];
        } else {
            String digits = r.account().accountNumber().replaceAll("[\\s-]", "");
            bankName = BankCatalog.BANKS.get(r.account().bankCode());
            if (bankName == null || !digits.matches("\\d{8,16}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "계좌 정보를 확인해 주세요.");
            bankCode = r.account().bankCode(); accountEnc = cipher.encrypt(digits); holder = r.account().holder().trim();
        }

        OffsetDateTime ws = (OffsetDateTime) pos.get("workStartAtValue"), we = (OffsetDateTime) pos.get("workEndAtValue");
        String name = jdbc.sql("SELECT name FROM users WHERE id = :u").param("u", userId).query(String.class).single();
        String departure = r.departureLocation() != null && !r.departureLocation().isBlank() ? r.departureLocation().trim() : (String) profile.get("defaultDepartureLocation");
        Map<String, Object> snapshot = snapshot(profile);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("positionId", positionId);
        canonical.put("actorUserId", userId);
        canonical.put("name", name);
        canonical.put("phone", phone);
        canonical.put("workStartAt", ws.toInstant().toString());
        canonical.put("workEndAt", we.toInstant().toString());
        canonical.put("profile", snapshot);
        canonical.put("taxIdentityId", taxId);
        canonical.put("termsVersion", termVersion);
        canonical.put("signatureSha256", sigSha);
        String hash = ApplyFileService.sha256(Json.write(canonical).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        long id;
        try {
            id = jdbc.sql("""
                    INSERT INTO applications (position_id, schedule_id, posting_id, actor_user_id, work_start_at, work_end_at, applicant_name, applicant_phone, departure_location,
                        profile_snapshot, tax_identity_id, bank_code, bank_name, account_number_enc, account_holder, message,
                        signed_at, signed_ip, signed_user_agent, signed_terms_version, snapshot_hash)
                    VALUES (:pos, :sch, :post, :u, :ws, :we, :name, :phone, :dep, CAST(:snap AS jsonb), :tax, :bc, :bn, :acc, :holder, :msg,
                        now(), CAST(:ip AS inet), :ua, :tv, :hash) RETURNING id
                    """)
                    .param("pos", positionId).param("sch", pos.get("scheduleId")).param("post", pos.get("postingId")).param("u", userId).param("ws", ws).param("we", we)
                    .param("name", name).param("phone", phone).param("dep", departure).param("snap", Json.write(snapshot)).param("tax", taxId)
                    .param("bc", bankCode).param("bn", bankName).param("acc", accountEnc).param("holder", holder).param("msg", r.message())
                    .param("ip", ip == null || ip.isBlank() ? "0.0.0.0" : ip).param("ua", userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())))
                    .param("tv", termVersion).param("hash", hash).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 지원한 역할이에요.");
        }
        jdbc.sql("INSERT INTO file_attachments (file_id, entity_type, entity_id, usage) VALUES (:f, 'APPLICATION', :a, 'APPLY_PHOTO')").param("f", r.photoFileId()).param("a", id).update();
        jdbc.sql("INSERT INTO file_attachments (file_id, entity_type, entity_id, usage) VALUES (:f, 'APPLICATION', :a, 'SIGNATURE')").param("f", r.signatureFileId()).param("a", id).update();
        jdbc.sql("UPDATE job_positions SET applied_count = applied_count + 1, updated_at = now() WHERE id = :id").param("id", positionId).update();
        history(id, null, "APPLIED", userId, null);
        audit.record(userId, "APPLICATION_CREATE", "APPLICATION", id, null);
        return Map.of("id", id, "status", "APPLIED", "snapshotHash", hash);
    }

    // ===== 취소 =====

    /**
     * 출연자의 취소. APPLIED 는 그냥 취소, SELECTED 는 거절(정원 반환), CONFIRMED 는 확정 취소 → 스케줄러 판정 대기(사유 필수, 연기·업체 사정으로 옮겨진 건은 판정 없음).
     */
    @Transactional
    public Map<String, Object> cancel(long userId, long applicationId, String reason) {
        Map<String, Object> a = jdbc.sql("""
                SELECT a.id, a.status, a.position_id, a.posting_id, a.moved_from_application_id, a.penalty_exempt, p.company_id, p.title AS posting_title
                  FROM applications a JOIN job_postings p ON p.id = a.posting_id WHERE a.id = :id AND a.actor_user_id = :u FOR UPDATE OF a
                """).param("id", applicationId).param("u", userId).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String status = (String) a.get("status");
        long positionId = ((Number) a.get("positionId")).longValue();
        switch (status) {
            case "APPLIED" -> jdbc.sql("UPDATE job_positions SET applied_count = GREATEST(applied_count - 1, 0), updated_at = now() WHERE id = :id").param("id", positionId).update();
            case "SELECTED" -> jdbc.sql("UPDATE job_positions SET selected_count = selected_count - 1, updated_at = now(), version = version + 1 WHERE id = :id").param("id", positionId).update();
            case "CONFIRMED" -> {
                if (reason == null || reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "취소 사유를 적어 주세요.");
                jdbc.sql("UPDATE job_positions SET confirmed_count = confirmed_count - 1, updated_at = now(), version = version + 1 WHERE id = :id").param("id", positionId).update();
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 끝난 지원이에요.");
        }
        jdbc.sql("UPDATE applications SET status = 'CANCELED_BY_ACTOR', canceled_at = now(), cancel_reason = :r, updated_at = now() WHERE id = :id").param("r", reason).param("id", applicationId).update();
        history(applicationId, status, "CANCELED_BY_ACTOR", userId, reason);
        resolveConflicts(applicationId);
        if ("CONFIRMED".equals(status) && a.get("movedFromApplicationId") == null && !Boolean.TRUE.equals(a.get("penaltyExempt"))) {
            long companyId = ((Number) a.get("companyId")).longValue();
            jdbc.sql("INSERT INTO cancellation_reviews (application_id, company_id, posting_id, actor_user_id) VALUES (:a, :c, :p, :u) ON CONFLICT DO NOTHING")
                    .param("a", applicationId).param("c", companyId).param("p", a.get("postingId")).param("u", userId).update();
            notifications.notifyCompany(companyId, List.of("SCHEDULER", "OWNER", "MANAGER"), "CANCEL_REVIEW", "확정 취소 패널티를 판정해 주세요",
                    a.get("postingTitle") + " — 확정한 출연자가 취소했어요.", "/company/cancellation-reviews");
        } else if ("CONFIRMED".equals(status) || "SELECTED".equals(status)) {
            notifications.notifyCompany(((Number) a.get("companyId")).longValue(), List.of("OWNER", "MANAGER", "SCHEDULER"), "ACTOR_CANCELED", "출연자가 취소했어요", a.get("postingTitle") + "", "/company/jobs/" + a.get("postingId"));
        }
        audit.record(userId, "APPLICATION_CANCEL", "APPLICATION", applicationId, null);
        return Map.of("id", applicationId, "status", "CANCELED_BY_ACTOR");
    }

    // ===== 내 지원 조회 =====

    public List<Map<String, Object>> mine(long userId, String status) {
        return jdbc.sql("""
                SELECT a.id, a.status, a.applied_at, a.work_start_at, a.work_end_at, a.response_due_at, a.departure_confirmed_at, a.conflict_override,
                       pos.title AS position_title, p.id AS posting_id, p.title AS posting_title, c.name AS company_name, a.penalty_exempt
                  FROM applications a JOIN job_positions pos ON pos.id = a.position_id JOIN job_postings p ON p.id = a.posting_id JOIN companies c ON c.id = p.company_id
                 WHERE a.actor_user_id = :u AND (CAST(:s AS text) IS NULL OR a.status = :s) ORDER BY a.work_start_at DESC, a.id DESC LIMIT 200
                """).param("u", userId).param("s", status).query(Rows.camel()).list();
    }

    /** 내 지원/합격 상세. 합격 통보 이후에는 현장 정보(집합지·연락처)를 보여 준다. */
    public Map<String, Object> mineDetail(long userId, long applicationId) {
        Map<String, Object> a = jdbc.sql("""
                SELECT a.id, a.status, a.posting_id, a.position_id, a.schedule_id, a.applied_at, a.work_start_at, a.work_end_at, a.response_due_at, a.confirmed_at, a.departure_requested_at,
                       a.departure_confirmed_at, a.conflict_override, a.message, a.departure_location, a.cancel_reason, a.snapshot_hash, a.signed_at, a.signed_terms_version,
                       a.moved_from_application_id, a.penalty_exempt,
                       pos.title AS position_title, pos.dress_code, pos.requirements, pos.pay_note, pos.hourly_wage_krw, pos.wage_mode,
                       p.title AS posting_title, p.conceal_details, p.contact_phone, p.company_id, c.name AS company_name,
                       s.shooting_location, s.meeting_place, s.meeting_address, s.latitude, s.longitude, s.note AS schedule_note,
                       COALESCE(pos.meeting_place, s.meeting_place) AS gather_place, COALESCE(pos.meeting_address, s.meeting_address) AS gather_address
                  FROM applications a JOIN job_positions pos ON pos.id = a.position_id JOIN job_schedules s ON s.id = a.schedule_id
                  JOIN job_postings p ON p.id = a.posting_id JOIN companies c ON c.id = p.company_id
                 WHERE a.id = :id AND a.actor_user_id = :u
                """).param("id", applicationId).param("u", userId).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean revealed = List.of("SELECTED", "CONFIRMED").contains(a.get("status")) || (a.get("confirmedAt") != null);
        if (!revealed) {
            for (String k : List.of("shootingLocation", "meetingPlace", "meetingAddress", "latitude", "longitude", "gatherPlace", "gatherAddress", "contactPhone", "scheduleNote")) a.put(k, null);
        } else {
            String place = a.get("gatherAddress") != null ? (String) a.get("gatherAddress") : (String) a.get("gatherPlace");
            if (place != null) a.put("mapQuery", place);
        }
        a.put("estimatedPayKrw", null);   // 보수는 공고에만 표시한다(합격서에는 금액 없음)
        a.put("conflicts", jdbc.sql("""
                SELECT sc.id, sc.acknowledged_at, sc.detected_at, CASE WHEN sc.application_a_id = :id THEN sc.application_b_id ELSE sc.application_a_id END AS other_application_id
                  FROM schedule_conflicts sc WHERE :id IN (sc.application_a_id, sc.application_b_id) AND sc.resolved_at IS NULL
                """).param("id", applicationId).query(Rows.camel()).list());
        a.put("timeNotice", jdbc.sql("SELECT value FROM app_settings WHERE key = 'time_estimate_notice'").query(String.class).optional().orElse(""));
        return a;
    }

    /** 합격서(금액 없음, 시간은 예상). 합격 통보를 받은 지원에만. */
    public Map<String, Object> offerLetter(long userId, long applicationId) {
        Map<String, Object> a = mineDetail(userId, applicationId);
        if (!List.of("SELECTED", "CONFIRMED").contains(a.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "합격 통보를 받은 지원만 합격서를 볼 수 있어요.");
        int gather = jdbc.sql("SELECT gather_tolerance_minutes FROM job_schedules WHERE id = :s").param("s", a.get("scheduleId")).query(Integer.class).single();
        int end = jdbc.sql("SELECT end_tolerance_minutes FROM job_schedules WHERE id = :s").param("s", a.get("scheduleId")).query(Integer.class).single();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "합격서");
        m.put("company", a.get("companyName"));
        m.put("posting", a.get("postingTitle"));
        m.put("role", a.get("positionTitle"));
        m.put("name", jdbc.sql("SELECT name FROM users WHERE id = :u").param("u", userId).query(String.class).single());
        m.put("expectedGatherAt", a.get("workStartAt"));
        m.put("gatherToleranceMinutes", gather);
        m.put("expectedEndAt", a.get("workEndAt"));
        m.put("endToleranceMinutes", end);
        m.put("place", a.get("gatherPlace"));
        m.put("address", a.get("gatherAddress"));
        m.put("dressCode", a.get("dressCode"));
        m.put("notes", a.get("requirements"));
        m.put("notices", List.of(a.get("timeNotice"), "근로계약서는 현장에서 따로 작성해요. 이 합격서는 근로계약서가 아니에요.", "보수는 공고에 적힌 내용을 따르며, 이 문서에는 금액이 없어요."));
        m.put("selectedAt", jdbc.sql("SELECT selected_at FROM applications WHERE id = :id").param("id", applicationId).query(java.sql.Timestamp.class).single().toInstant().toString());
        return m;
    }

    /** 지원서 증빙 사본: 서명·사진은 서명 URL, 스냅샷 해시와 동의 약관 버전 포함. 주민번호·계좌 전체는 포함하지 않는다. */
    public Map<String, Object> document(long userId, long applicationId) {
        Map<String, Object> a = jdbc.sql("""
                SELECT id, status, applicant_name, applicant_phone, profile_snapshot, applied_at, signed_at, signed_terms_version, snapshot_hash, bank_name, account_holder, work_start_at, work_end_at
                  FROM applications WHERE id = :id AND actor_user_id = :u
                """).param("id", applicationId).param("u", userId).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        a.put("files", jdbc.sql("""
                SELECT fa.usage, f.public_id FROM file_attachments fa JOIN files f ON f.id = fa.file_id WHERE fa.entity_type = 'APPLICATION' AND fa.entity_id = :id AND f.status = 'ACTIVE'
                """).param("id", applicationId).query((rs, n) -> Map.<String, Object>of("usage", rs.getString(1), "url", urls.urlFor(rs.getObject(2, UUID.class)))).list());
        return a;
    }

    /** 내 달력: 달에 걸친 합격 통보·확정 일정. */
    public List<Map<String, Object>> myCalendar(long userId, String month) {
        java.time.YearMonth ym;
        try {
            ym = java.time.YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return jdbc.sql("""
                SELECT a.id, a.status, a.work_start_at, a.work_end_at, pos.title AS position_title, p.title AS posting_title, c.name AS company_name, a.conflict_override
                  FROM applications a JOIN job_positions pos ON pos.id = a.position_id JOIN job_postings p ON p.id = a.posting_id JOIN companies c ON c.id = p.company_id
                 WHERE a.actor_user_id = :u AND a.status IN ('APPLIED','SELECTED','CONFIRMED')
                   AND (a.work_start_at AT TIME ZONE 'Asia/Seoul')::date <= CAST(:last AS date)
                   AND ((a.work_end_at AT TIME ZONE 'Asia/Seoul') - interval '1 second')::date >= CAST(:first AS date)
                 ORDER BY a.work_start_at
                """).param("u", userId).param("first", ym.atDay(1)).param("last", ym.atEndOfMonth()).query(Rows.camel()).list();
    }

    // ===== 공용 내부 =====

    /** 이 지원의 활성 충돌을 해소(지원 취소·업체 취소 시). */
    void resolveConflicts(long applicationId) {
        jdbc.sql("""
                UPDATE schedule_conflicts SET resolved_at = now(), resolution = CASE WHEN application_a_id = :a THEN 'CANCELED_A' ELSE 'CANCELED_B' END
                 WHERE :a IN (application_a_id, application_b_id) AND resolved_at IS NULL
                """).param("a", applicationId).update();
    }

    void history(long applicationId, String from, String to, Long by, String reason) {
        jdbc.sql("INSERT INTO application_status_histories (application_id, from_status, to_status, changed_by, reason) VALUES (:a, :f, :t, :u, :r)")
                .param("a", applicationId).param("f", from).param("t", to).param("u", by).param("r", reason).update();
    }

    /**
     * 같은 출연자의 SELECTED/CONFIRMED 중 [start, end] 와 겹치거나 conflict_gap_minutes 보다 가까운 지원(자기 자신 제외).
     * override 로 확정된 행도 포함한다(앱은 모든 SELECTED/CONFIRMED 와의 겹침을 직접 검사 — CLAUDE.md §2-4).
     */
    List<Map<String, Object>> overlapping(long userId, OffsetDateTime start, OffsetDateTime end, long excludeApplicationId) {
        int gap = Integer.parseInt(jdbc.sql("SELECT value FROM app_settings WHERE key = 'conflict_gap_minutes'").query(String.class).optional().orElse("120"));
        return jdbc.sql("""
                SELECT a.id, a.status, a.work_start_at, a.work_end_at, pos.title AS position_title, p.title AS posting_title, p.company_id, c.name AS company_name
                  FROM applications a JOIN job_positions pos ON pos.id = a.position_id JOIN job_postings p ON p.id = a.posting_id JOIN companies c ON c.id = p.company_id
                 WHERE a.actor_user_id = :u AND a.status IN ('SELECTED','CONFIRMED') AND a.id <> :ex
                   AND tstzrange(a.work_start_at - make_interval(mins => :half), a.work_end_at + make_interval(mins => :half)) && tstzrange(CAST(:s AS timestamptz) - make_interval(mins => :half), CAST(:e AS timestamptz) + make_interval(mins => :half))
                """).param("u", userId).param("ex", excludeApplicationId).param("half", gap / 2).param("s", start).param("e", end).query(Rows.camel()).list();
    }

    Map<String, Object> positionForApply(long positionId, boolean mustBeOpen) {
        Map<String, Object> pos = jdbc.sql("""
                SELECT pos.id, pos.title, pos.schedule_id, pos.posting_id, p.company_id, pos.gender_req, pos.birth_year_min, pos.birth_year_max, pos.height_min, pos.height_max,
                       pos.weight_min, pos.weight_max, pos.hair_lengths, pos.tattoo_req, pos.allow_minor, pos.status AS position_status, s.status AS schedule_status,
                       p.status AS posting_status, p.apply_deadline_at, COALESCE(pos.start_at, s.start_at) AS work_start_at, COALESCE(pos.end_at, s.end_at) AS work_end_at
                  FROM job_positions pos JOIN job_schedules s ON s.id = pos.schedule_id JOIN job_postings p ON p.id = pos.posting_id JOIN companies c ON c.id = p.company_id
                 WHERE pos.id = :id AND p.deleted_at IS NULL AND c.status = 'APPROVED'
                """).param("id", positionId).query((rs, n) -> {
                    Map<String, Object> m = Rows.camel().mapRow(rs, n);
                    m.put("workStartAtValue", rs.getObject("work_start_at", OffsetDateTime.class));
                    m.put("workEndAtValue", rs.getObject("work_end_at", OffsetDateTime.class));
                    return m;
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean open = "OPEN".equals(pos.get("positionStatus")) && "OPEN".equals(pos.get("scheduleStatus")) && "OPEN".equals(pos.get("postingStatus"))
                && (pos.get("applyDeadlineAt") == null || OffsetDateTime.parse((String) pos.get("applyDeadlineAt")).isAfter(OffsetDateTime.now()))
                && ((OffsetDateTime) pos.get("workStartAtValue")).isAfter(OffsetDateTime.now());
        if (mustBeOpen && !open) throw new ResponseStatusException(HttpStatus.CONFLICT, "지원이 마감된 공고예요.");
        pos.put("open", open);
        return pos;
    }

    private Map<String, Object> profile(long userId) {
        return jdbc.sql("SELECT * FROM actor_profiles WHERE user_id = :u").param("u", userId).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("birthDate", rs.getObject("birth_date", LocalDate.class));
            m.put("gender", rs.getString("gender"));
            m.put("heightCm", rs.getInt("height_cm"));
            m.put("weightKg", rs.getInt("weight_kg"));
            m.put("topSize", rs.getString("top_size"));
            m.put("bottomSize", rs.getString("bottom_size"));
            m.put("shoeSize", rs.getObject("shoe_size"));
            m.put("hairLength", rs.getString("hair_length"));
            m.put("hairStyle", rs.getString("hair_style"));
            m.put("hasTattoo", rs.getBoolean("has_tattoo"));
            m.put("canDrive", rs.getBoolean("can_drive"));
            m.put("hasVehicle", rs.getBoolean("has_vehicle"));
            m.put("homePostalCode", rs.getString("home_postal_code"));
            m.put("homeAddress", rs.getString("home_address"));
            m.put("homeAddressDetail", rs.getString("home_address_detail"));
            m.put("defaultDepartureLocation", rs.getString("default_departure_location"));
            return m;
        }).optional().orElse(null);
    }

    /** 지원서에 복제하는 스냅샷(주민번호는 넣지 않는다). 키는 인원 정보표 뷰가 읽는 snake_case. */
    private static Map<String, Object> snapshot(Map<String, Object> p) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("gender", p.get("gender"));
        s.put("birth_date", p.get("birthDate").toString());
        s.put("height_cm", p.get("heightCm"));
        s.put("weight_kg", p.get("weightKg"));
        s.put("top_size", p.get("topSize"));
        s.put("bottom_size", p.get("bottomSize"));
        s.put("shoe_size", p.get("shoeSize"));
        s.put("hair_length", p.get("hairLength"));
        s.put("hair_style", p.get("hairStyle"));
        s.put("has_tattoo", p.get("hasTattoo"));
        s.put("can_drive", p.get("canDrive"));
        s.put("has_vehicle", p.get("hasVehicle"));
        s.put("home_postal_code", p.get("homePostalCode"));
        s.put("home_address", p.get("homeAddress"));
        s.put("home_address_detail", p.get("homeAddressDetail"));
        return s;
    }

    private static Map<String, Object> summary(Map<String, Object> p) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("gender", p.get("gender"));
        s.put("birthDate", p.get("birthDate").toString());
        s.put("heightCm", p.get("heightCm"));
        s.put("weightKg", p.get("weightKg"));
        s.put("hairLength", p.get("hairLength"));
        s.put("hasTattoo", p.get("hasTattoo"));
        s.put("defaultDepartureLocation", p.get("defaultDepartureLocation"));
        return s;
    }

    private Map<String, Object> pendingESignatureTerm(long userId) {
        return jdbc.sql("""
                SELECT t.id, t.version, t.content FROM terms t WHERE t.type = 'E_SIGNATURE' AND t.effective_at <= now()
                   AND NOT EXISTS (SELECT 1 FROM user_consents uc WHERE uc.user_id = :u AND uc.term_id = t.id AND uc.agreed)
                 ORDER BY t.effective_at DESC LIMIT 1
                """).param("u", userId).query(Rows.camel()).optional().orElse(null);
    }

    private String ownFreeFile(long userId, long fileId) {
        var row = jdbc.sql("""
                SELECT checksum_sha256 FROM files f WHERE f.id = :f AND f.uploader_user_id = :u AND f.status = 'ACTIVE'
                   AND NOT EXISTS (SELECT 1 FROM file_attachments fa WHERE fa.file_id = f.id)
                """).param("f", fileId).param("u", userId).query(String.class).optional();
        return row.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 또는 서명 파일을 다시 올려 주세요."));
    }

    private String phoneOf(long userId) {
        return jdbc.sql("SELECT phone FROM users WHERE id = :u").param("u", userId).query(String.class).optional().orElse(null);
    }

    private boolean hasRrn(long userId) {
        return jdbc.sql("SELECT count(*) FROM actor_tax_identities WHERE user_id = :u AND is_active").param("u", userId).query(Integer.class).single() > 0;
    }

    private boolean requireRrn() {
        return "true".equals(jdbc.sql("SELECT value FROM app_settings WHERE key = 'require_rrn_before_apply'").query(String.class).optional().orElse("true"));
    }

    private boolean minorApplyEnabled() {
        return "true".equals(jdbc.sql("SELECT value FROM app_settings WHERE key = 'minor_apply_enabled'").query(String.class).optional().orElse("false"));
    }
}
