package kr.bojohelp.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.bojohelp.audit.AuditService;
import kr.bojohelp.common.ConflictingScheduleException;
import kr.bojohelp.common.Json;
import kr.bojohelp.common.Rows;
import kr.bojohelp.message.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 선발 흐름: 업체 합격 통보(SELECTED) → 출연자 확정(CONFIRMED). 정원(selected+confirmed ≤ headcount)은 역할 행을 잠근 채 검사해
 * 동시에 여러 명을 통보해도 넘지 않는다(DB CHECK 가 마지막 방어). 마감 뒤 재선발은 일정 ±1일에 다른 합격이 없는 사람만.
 */
@Service
public class SelectionService {

    private final JdbcClient jdbc;
    private final ApplicationService apps;
    private final NotificationService notifications;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public SelectionService(JdbcClient jdbc, ApplicationService apps, NotificationService notifications, AuditService audit, TransactionTemplate tx) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.apps = apps;
        this.notifications = notifications;
        this.audit = audit;
    }

    private int setting(String key, int def) {
        return Integer.parseInt(jdbc.sql("SELECT value FROM app_settings WHERE key = :k").param("k", key).query(String.class).optional().orElse(String.valueOf(def)));
    }

    // ===== 업체: 합격 통보 / 불합격 =====

    /** 지원자별 결과를 돌려준다(일부만 실패할 수 있음). 지원은 하나씩 별도로 처리해 한 건의 실패가 다른 건을 되돌리지 않는다. */
    public List<Map<String, Object>> decide(long userId, long companyId, List<Long> applicationIds, String decision, Integer responseHours) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Long id : applicationIds) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("applicationId", id);
            try {
                if ("SELECT".equals(decision)) tx.executeWithoutResult(st -> selectOne(userId, companyId, id, responseHours));
                else if ("REJECT".equals(decision)) tx.executeWithoutResult(st -> rejectOne(userId, companyId, id));
                else throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                r.put("ok", true);
            } catch (ResponseStatusException e) {
                if (e.getStatusCode().value() == 400 && !(e.getReason() != null && e.getReason().length() > 0)) throw e;
                r.put("ok", false);
                r.put("status", e.getStatusCode().value());
                r.put("reason", e.getReason());
            }
            results.add(r);
        }
        return results;
    }

    void selectOne(long userId, long companyId, long applicationId, Integer responseHours) {
        Map<String, Object> a = jdbc.sql("""
                SELECT a.id, a.status, a.position_id, a.actor_user_id, a.work_start_at, a.work_end_at, p.company_id, p.status AS posting_status, p.title AS posting_title,
                       pos.title AS position_title, pos.hourly_wage_krw, pos.status AS position_status, pos.dress_code, pos.headcount,
                       COALESCE(pos.meeting_place, s.meeting_place) AS meeting_place
                  FROM applications a JOIN job_postings p ON p.id = a.posting_id JOIN job_positions pos ON pos.id = a.position_id JOIN job_schedules s ON s.id = a.schedule_id
                 WHERE a.id = :id
                """).param("id", applicationId).query((rs, n) -> {
                    Map<String, Object> m = Rows.camel().mapRow(rs, n);
                    m.put("ws", rs.getObject("work_start_at", OffsetDateTime.class));
                    m.put("we", rs.getObject("work_end_at", OffsetDateTime.class));
                    return m;
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "지원서를 찾을 수 없어요."));
        if (((Number) a.get("companyId")).longValue() != companyId) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "지원서를 찾을 수 없어요.");
        long positionId = ((Number) a.get("positionId")).longValue();
        // 역할 행을 먼저 잠가 정원 검사를 직렬화한다
        var counts = jdbc.sql("SELECT headcount, selected_count, confirmed_count, status FROM job_positions WHERE id = :id FOR UPDATE").param("id", positionId)
                .query((rs, n) -> new Object[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4)}).single();
        var cur = jdbc.sql("SELECT status FROM applications WHERE id = :id FOR UPDATE").param("id", applicationId).query(String.class).single();
        boolean reselect = "AUTO_REJECTED".equals(cur);
        if (!"APPLIED".equals(cur) && !reselect) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 지원이에요.");
        if ("CANCELED".equals(a.get("postingStatus")) || "CANCELED".equals(counts[3])) throw new ResponseStatusException(HttpStatus.CONFLICT, "취소된 공고예요.");
        OffsetDateTime ws = (OffsetDateTime) a.get("ws"), we = (OffsetDateTime) a.get("we");
        if (!ws.isAfter(OffsetDateTime.now())) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 시작된 일정이에요.");
        if ((int) counts[1] + (int) counts[2] >= (int) counts[0]) throw new ResponseStatusException(HttpStatus.CONFLICT, "정원이 찼어요.");

        long actorId = ((Number) a.get("actorUserId")).longValue();
        if (reselect || !"OPEN".equals(a.get("postingStatus"))) {   // 마감 뒤 재선발: 일정 ±N일에 다른 합격이 없는 사람만
            int window = setting("reserve_candidate_window_days", 1);
            int busy = jdbc.sql("""
                    SELECT count(*) FROM applications o WHERE o.actor_user_id = :u AND o.id <> :id AND o.status IN ('SELECTED','CONFIRMED')
                       AND (o.work_start_at AT TIME ZONE 'Asia/Seoul')::date <= ((CAST(:we AS timestamptz) AT TIME ZONE 'Asia/Seoul')::date + :w)
                       AND ((o.work_end_at AT TIME ZONE 'Asia/Seoul') - interval '1 second')::date >= ((CAST(:ws AS timestamptz) AT TIME ZONE 'Asia/Seoul')::date - :w)
                    """).param("u", actorId).param("id", applicationId).param("ws", ws).param("we", we).param("w", window).query(Integer.class).single();
            if (busy > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "그 무렵 다른 일정이 있는 분이에요.");
        }

        int half = setting("conflict_gap_minutes", 120) / 2;
        int hours = responseHours != null && responseHours > 0 && responseHours <= 72 ? responseHours : setting("selected_response_hours", 12);
        OffsetDateTime due = OffsetDateTime.now().plusHours(hours);
        OffsetDateTime latest = ws.minusHours(3);   // 집합이 더 빠르면 집합 3시간 전까지
        if (latest.isBefore(due)) due = latest;
        OffsetDateTime floor = OffsetDateTime.now().plusMinutes(30);
        if (due.isBefore(floor)) due = floor;

        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("hourlyWageKrw", a.get("hourlyWageKrw"));
        condition.put("workStartAt", ws.toInstant().toString());
        condition.put("workEndAt", we.toInstant().toString());
        condition.put("meetingPlace", a.get("meetingPlace"));
        condition.put("dressCode", a.get("dressCode"));
        jdbc.sql("""
                UPDATE applications SET status = 'SELECTED', selected_by = :by, selected_at = now(), response_due_at = :due,
                       reserved_range = tstzrange(CAST(:ws AS timestamptz) - make_interval(mins => :half), CAST(:we AS timestamptz) + make_interval(mins => :half)),
                       condition_snapshot = CAST(:cond AS jsonb), updated_at = now() WHERE id = :id
                """).param("by", userId).param("due", due).param("ws", ws).param("we", we).param("half", half).param("cond", Json.write(condition)).param("id", applicationId).update();
        jdbc.sql("UPDATE job_positions SET selected_count = selected_count + 1, applied_count = GREATEST(applied_count - 1, 0), updated_at = now(), version = version + 1 WHERE id = :id").param("id", positionId).update();
        apps.history(applicationId, cur, "SELECTED", userId, reselect ? "마감 후 재선발" : null);
        notifications.notify(actorId, "SELECTED", "🎉 뽑혔어요! 확정해 주세요", a.get("postingTitle") + " · " + a.get("positionTitle"), "/me/applications/" + applicationId, true, false, Map.of("applicationId", applicationId), null);
        audit.record(userId, "APPLICATION_SELECT", "APPLICATION", applicationId, null);
    }

    void rejectOne(long userId, long companyId, long applicationId) {
        Map<String, Object> a = jdbc.sql("SELECT a.status, a.position_id, a.actor_user_id, p.company_id FROM applications a JOIN job_postings p ON p.id = a.posting_id WHERE a.id = :id FOR UPDATE OF a")
                .param("id", applicationId).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "지원서를 찾을 수 없어요."));
        if (((Number) a.get("companyId")).longValue() != companyId) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "지원서를 찾을 수 없어요.");
        if (!"APPLIED".equals(a.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 지원이에요.");
        jdbc.sql("UPDATE applications SET status = 'REJECTED', updated_at = now() WHERE id = :id").param("id", applicationId).update();
        jdbc.sql("UPDATE job_positions SET applied_count = GREATEST(applied_count - 1, 0), updated_at = now() WHERE id = :id").param("id", a.get("positionId")).update();
        apps.history(applicationId, "APPLIED", "REJECTED", userId, null);
        notifications.notify(((Number) a.get("actorUserId")).longValue(), "APPLICATION_REJECTED", "이번에는 함께하지 못해요", "지원해 주셔서 고마워요. 다음 공고에서 만나요.", "/me/applications/" + applicationId);
    }

    // ===== 출연자: 확정 =====

    @Transactional
    public Map<String, Object> confirm(long userId, long applicationId, boolean acknowledgeConflict) {
        Map<String, Object> a = jdbc.sql("""
                SELECT a.id, a.status, a.position_id, a.posting_id, a.work_start_at, a.work_end_at, a.response_due_at, p.company_id, p.title AS posting_title
                  FROM applications a JOIN job_postings p ON p.id = a.posting_id WHERE a.id = :id AND a.actor_user_id = :u FOR UPDATE OF a
                """).param("id", applicationId).param("u", userId).query((rs, n) -> {
                    Map<String, Object> m = Rows.camel().mapRow(rs, n);
                    m.put("ws", rs.getObject("work_start_at", OffsetDateTime.class));
                    m.put("we", rs.getObject("work_end_at", OffsetDateTime.class));
                    m.put("due", rs.getObject("response_due_at", OffsetDateTime.class));
                    return m;
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String status = (String) a.get("status");
        if ("CONFIRMED".equals(status)) return Map.of("id", applicationId, "status", "CONFIRMED");   // 두 번 눌러도 안전
        if ("EXPIRED".equals(status)) throw new ResponseStatusException(HttpStatus.GONE, "응답 시간이 지나 합격이 만료되었어요.");
        if (!"SELECTED".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "확정할 수 있는 상태가 아니에요.");
        if (((OffsetDateTime) a.get("due")).isBefore(OffsetDateTime.now())) throw new ResponseStatusException(HttpStatus.GONE, "응답 시간이 지나 합격이 만료되었어요.");
        OffsetDateTime ws = (OffsetDateTime) a.get("ws"), we = (OffsetDateTime) a.get("we");
        if (!ws.isAfter(OffsetDateTime.now())) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 시작된 일정이에요.");

        List<Map<String, Object>> overlaps = apps.overlapping(userId, ws, we, applicationId);
        if (!overlaps.isEmpty() && !acknowledgeConflict) throw new ConflictingScheduleException(overlaps);

        long positionId = ((Number) a.get("positionId")).longValue();
        jdbc.sql("SELECT id FROM job_positions WHERE id = :id FOR UPDATE").param("id", positionId).query(Long.class).single();
        jdbc.sql("UPDATE job_positions SET selected_count = selected_count - 1, confirmed_count = confirmed_count + 1, updated_at = now(), version = version + 1 WHERE id = :id").param("id", positionId).update();
        try {
            jdbc.sql("UPDATE applications SET status = 'CONFIRMED', confirmed_at = now(), conflict_override = :o, updated_at = now() WHERE id = :id").param("o", !overlaps.isEmpty()).param("id", applicationId).update();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ConflictingScheduleException(overlaps);   // DB 최후 방어(EXCLUDE)에 걸린 경우
        }
        jdbc.sql("UPDATE actor_profiles SET confirmed_count = confirmed_count + 1, updated_at = now() WHERE user_id = :u").param("u", userId).update();
        apps.history(applicationId, "SELECTED", "CONFIRMED", userId, overlaps.isEmpty() ? null : "겹치는 일정을 알고 확정");

        long companyId = ((Number) a.get("companyId")).longValue();
        notifications.notifyCompany(companyId, List.of("OWNER", "MANAGER", "SCHEDULER"), "ACTOR_CONFIRMED", "출연자가 확정했어요", a.get("postingTitle") + "", "/company/jobs/" + a.get("postingId"));
        List<Map<String, Object>> confirmedOverlaps = overlaps.stream().filter(o -> "CONFIRMED".equals(o.get("status"))).toList();
        if (!confirmedOverlaps.isEmpty()) raiseConflict(userId, applicationId, companyId, a.get("postingTitle") + "", confirmedOverlaps);   // 업체 알림·반복 경고는 이미 확정된 일정과 겹칠 때만
        audit.record(userId, "APPLICATION_CONFIRM", "APPLICATION", applicationId, null);
        return Map.of("id", applicationId, "status", "CONFIRMED", "conflict", !overlaps.isEmpty());
    }

    private void raiseConflict(long userId, long applicationId, long companyId, String title, List<Map<String, Object>> overlaps) {
        for (Map<String, Object> o : overlaps) {
            long otherId = ((Number) o.get("id")).longValue();
            long otherCompany = ((Number) o.get("companyId")).longValue();
            Long cid = jdbc.sql("""
                    INSERT INTO schedule_conflicts (actor_user_id, application_a_id, application_b_id, last_alert_at, alert_count) VALUES (:u, :a, :b, now(), 1)
                    ON CONFLICT (application_a_id, application_b_id) DO NOTHING RETURNING id
                    """).param("u", userId).param("a", otherId).param("b", applicationId).query(Long.class).optional().orElse(null);
            if (cid == null) continue;
            String txt = "한 출연자가 겹치는 두 일정을 모두 확정했어요: " + o.get("postingTitle") + " / " + title;
            notifications.notifyCompany(companyId, List.of("OWNER", "MANAGER", "SCHEDULER"), "SCHEDULE_CONFLICT", "일정이 겹치는 출연자가 있어요", txt, "/company/jobs/" + o.get("postingId"));
            if (otherCompany != companyId) notifications.notifyCompany(otherCompany, List.of("OWNER", "MANAGER", "SCHEDULER"), "SCHEDULE_CONFLICT", "일정이 겹치는 출연자가 있어요", txt, "/company/jobs");
            notifications.notify(userId, "SCHEDULE_CONFLICT", "⚠️ 일정이 겹쳐요", "확인을 누를 때까지 1시간마다 알려 드려요.", "/me/conflicts", true, true, Map.of("conflictId", cid), null);
        }
    }

    /** 충돌 경고 확인: 반복 알림을 멈춘다. */
    @Transactional
    public void acknowledgeConflict(long userId, long conflictId) {
        int n = jdbc.sql("UPDATE schedule_conflicts SET acknowledged_at = COALESCE(acknowledged_at, now()) WHERE id = :id AND actor_user_id = :u").param("id", conflictId).param("u", userId).update();
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        jdbc.sql("UPDATE notifications SET acked_at = COALESCE(acked_at, now()), read_at = COALESCE(read_at, now()) WHERE user_id = :u AND requires_ack AND (payload->>'conflictId')::bigint = :id").param("u", userId).param("id", conflictId).update();
    }

    public List<Map<String, Object>> myConflicts(long userId) {
        return jdbc.sql("""
                SELECT sc.id, sc.detected_at, sc.acknowledged_at, sc.resolved_at, sc.alert_count,
                       a.id AS application_a_id, pa.title AS posting_a, a.work_start_at AS a_start, a.work_end_at AS a_end, a.status AS a_status,
                       b.id AS application_b_id, pb.title AS posting_b, b.work_start_at AS b_start, b.work_end_at AS b_end, b.status AS b_status
                  FROM schedule_conflicts sc JOIN applications a ON a.id = sc.application_a_id JOIN job_postings pa ON pa.id = a.posting_id
                  JOIN applications b ON b.id = sc.application_b_id JOIN job_postings pb ON pb.id = b.posting_id
                 WHERE sc.actor_user_id = :u AND sc.resolved_at IS NULL ORDER BY sc.detected_at DESC
                """).param("u", userId).query(Rows.camel()).list();
    }

    // ===== 업체 취소 =====

    @Transactional
    public Map<String, Object> companyCancel(long userId, long companyId, long applicationId, String reason) {
        Map<String, Object> a = jdbc.sql("SELECT a.status, a.position_id, a.actor_user_id, a.posting_id, p.company_id, p.title AS posting_title FROM applications a JOIN job_postings p ON p.id = a.posting_id WHERE a.id = :id FOR UPDATE OF a")
                .param("id", applicationId).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (((Number) a.get("companyId")).longValue() != companyId) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String status = (String) a.get("status");
        long positionId = ((Number) a.get("positionId")).longValue();
        if ("SELECTED".equals(status)) jdbc.sql("UPDATE job_positions SET selected_count = selected_count - 1, updated_at = now(), version = version + 1 WHERE id = :id").param("id", positionId).update();
        else if ("CONFIRMED".equals(status)) jdbc.sql("UPDATE job_positions SET confirmed_count = confirmed_count - 1, updated_at = now(), version = version + 1 WHERE id = :id").param("id", positionId).update();
        else throw new ResponseStatusException(HttpStatus.CONFLICT, "합격 통보·확정 상태의 지원만 취소할 수 있어요.");
        jdbc.sql("UPDATE applications SET status = 'CANCELED_BY_COMPANY', penalty_exempt = TRUE, canceled_at = now(), cancel_reason = :r, updated_at = now() WHERE id = :id").param("r", reason).param("id", applicationId).update();
        apps.history(applicationId, status, "CANCELED_BY_COMPANY", userId, reason);
        apps.resolveConflicts(applicationId);
        notifications.notify(((Number) a.get("actorUserId")).longValue(), "CANCELED_BY_COMPANY", "합격이 취소되었어요", "업체 사정으로 취소되었어요. 불이익은 없어요.", "/me/applications/" + applicationId, true, false, null, null);
        audit.record(userId, "APPLICATION_COMPANY_CANCEL", "APPLICATION", applicationId, null);
        return Map.of("id", applicationId, "status", "CANCELED_BY_COMPANY");
    }

    // ===== 시스템: 응답 기한·충돌 재알림 (스케줄러가 호출) =====

    /** 합격 통보 후 기한 안에 응답하지 않은 지원을 EXPIRED 로 돌리고 정원을 돌려준다. */
    @Transactional
    public int expireOverdue() {
        List<Map<String, Object>> due = jdbc.sql("SELECT id, position_id, actor_user_id, posting_id FROM applications WHERE status = 'SELECTED' AND response_due_at < now() ORDER BY id FOR UPDATE SKIP LOCKED")
                .query(Rows.camel()).list();
        for (Map<String, Object> a : due) {
            long id = ((Number) a.get("id")).longValue();
            jdbc.sql("UPDATE job_positions SET selected_count = selected_count - 1, updated_at = now(), version = version + 1 WHERE id = :id").param("id", a.get("positionId")).update();
            jdbc.sql("UPDATE applications SET status = 'EXPIRED', updated_at = now() WHERE id = :id").param("id", id).update();
            apps.history(id, "SELECTED", "EXPIRED", null, "응답 기한 경과");
            notifications.notify(((Number) a.get("actorUserId")).longValue(), "SELECTION_EXPIRED", "응답 시간이 지났어요", "합격 확정 시간이 지나 자리가 다른 분께 넘어갈 수 있어요.", "/me/applications/" + id);
        }
        return due.size();
    }

    /** 확인하지 않은 일정 충돌 경고를 interval 마다 다시 보낸다. */
    @Transactional
    public int remindOpenConflicts() {
        int minutes = setting("conflict_reminder_interval_minutes", 60);
        List<Map<String, Object>> list = jdbc.sql("""
                SELECT id, actor_user_id FROM schedule_conflicts WHERE acknowledged_at IS NULL AND resolved_at IS NULL AND (last_alert_at IS NULL OR last_alert_at < now() - make_interval(mins => :m)) FOR UPDATE SKIP LOCKED
                """).param("m", minutes).query(Rows.camel()).list();
        for (Map<String, Object> c : list) {
            long id = ((Number) c.get("id")).longValue();
            notifications.notify(((Number) c.get("actorUserId")).longValue(), "SCHEDULE_CONFLICT", "⚠️ 겹치는 일정을 확인해 주세요", "확인 버튼을 누를 때까지 계속 알려 드려요.", "/me/conflicts", true, true, Map.of("conflictId", id), null);
            jdbc.sql("UPDATE schedule_conflicts SET last_alert_at = now(), alert_count = alert_count + 1 WHERE id = :id").param("id", id).update();
        }
        return list.size();
    }
}
