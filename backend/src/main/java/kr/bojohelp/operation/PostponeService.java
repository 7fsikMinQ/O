package kr.bojohelp.operation;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import kr.bojohelp.audit.AuditService;
import kr.bojohelp.common.Rows;
import kr.bojohelp.message.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 일정 연기(CLAUDE.md §2-11): 같은 공고 안에 새 일정(+역할 복제)을 만들고, 지원·합격자를 새 일정으로 자동 이동한다.
 * 합격 통보·확정 인원은 새 일정에서 SELECTED 로 시작해 출연자가 다시 확정해야 정원이 채워지며, 원본은 패널티 없이 종료된다.
 * 연기된 일정을 출연자가 취소해도 패널티는 없다(이동 지원은 판정 대상이 아니다).
 */
@Service
public class PostponeService {

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final AuditService audit;

    public PostponeService(JdbcClient jdbc, NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> postpone(long userId, long scheduleId, OffsetDateTime newStart, OffsetDateTime newEnd, String reason) {
        Map<String, Object> s = jdbc.sql("SELECT s.id, s.posting_id, s.status, s.start_at, s.end_at, p.title FROM job_schedules s JOIN job_postings p ON p.id = s.posting_id WHERE s.id = :id FOR UPDATE OF s")
                .param("id", scheduleId).query((rs, n) -> {
                    Map<String, Object> m = Rows.camel().mapRow(rs, n);
                    m.put("start", rs.getObject("start_at", OffsetDateTime.class));
                    return m;
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!List.of("OPEN", "CLOSED").contains(s.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "연기할 수 없는 일정이에요.");
        Duration len = Duration.between(newStart, newEnd);
        if (len.compareTo(Duration.ofHours(1)) < 0 || len.compareTo(Duration.ofDays(21)) > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "일정은 1시간 이상 21일 이하로 정해 주세요.");
        if (!newStart.isAfter(OffsetDateTime.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "새 일정은 지금보다 뒤여야 해요.");
        long postingId = ((Number) s.get("postingId")).longValue();
        Duration shift = Duration.between((OffsetDateTime) s.get("start"), newStart);
        int half = Integer.parseInt(jdbc.sql("SELECT value FROM app_settings WHERE key = 'conflict_gap_minutes'").query(String.class).optional().orElse("120")) / 2;
        int hours = Integer.parseInt(jdbc.sql("SELECT value FROM app_settings WHERE key = 'selected_response_hours'").query(String.class).optional().orElse("12"));

        long newSchedule = jdbc.sql("""
                INSERT INTO job_schedules (posting_id, start_at, end_at, gather_tolerance_minutes, end_tolerance_minutes, is_lodging, region_id, shooting_location, meeting_place, meeting_address,
                       latitude, longitude, field_leader_user_id, note, status, postponed_from_schedule_id)
                SELECT posting_id, :ns, :ne, gather_tolerance_minutes, end_tolerance_minutes, is_lodging, region_id, shooting_location, meeting_place, meeting_address,
                       latitude, longitude, field_leader_user_id, note, 'OPEN', id FROM job_schedules WHERE id = :id RETURNING id
                """).param("ns", newStart).param("ne", newEnd).param("id", scheduleId).query(Long.class).single();

        List<Map<String, Object>> positions = jdbc.sql("SELECT id FROM job_positions WHERE schedule_id = :s ORDER BY id").param("s", scheduleId).query(Rows.camel()).list();
        int moved = 0;
        for (Map<String, Object> p : positions) {
            long oldPos = ((Number) p.get("id")).longValue();
            long newPos = jdbc.sql("""
                    INSERT INTO job_positions (schedule_id, posting_id, category_id, title, start_at, end_at, meeting_place, meeting_address, headcount, gender_req, birth_year_min, birth_year_max,
                           height_min, height_max, weight_min, weight_max, hair_lengths, tattoo_req, allow_minor, wage_mode, hourly_wage_krw, min_wage_year, pay_note, dress_code, requirements, cloned_from_position_id)
                    SELECT :ns, posting_id, category_id, title, start_at + make_interval(secs => :sec), end_at + make_interval(secs => :sec), meeting_place, meeting_address, headcount, gender_req, birth_year_min, birth_year_max,
                           height_min, height_max, weight_min, weight_max, hair_lengths, tattoo_req, allow_minor, wage_mode, hourly_wage_krw, min_wage_year, pay_note, dress_code, requirements, id
                      FROM job_positions WHERE id = :id RETURNING id
                    """).param("ns", newSchedule).param("sec", (double) shift.getSeconds()).param("id", oldPos).query(Long.class).single();   // 역할별 시간 override 도 같은 만큼 민다
            List<Map<String, Object>> apps = jdbc.sql("SELECT id, actor_user_id, status FROM applications WHERE position_id = :p AND status IN ('APPLIED','SELECTED','CONFIRMED') ORDER BY id FOR UPDATE")
                    .param("p", oldPos).query(Rows.camel()).list();
            int selected = 0, applied = 0;
            for (Map<String, Object> a : apps) {
                long oldApp = ((Number) a.get("id")).longValue();
                String oldStatus = (String) a.get("status");
                boolean wasPicked = !"APPLIED".equals(oldStatus);
                long newApp = jdbc.sql("""
                        INSERT INTO applications (position_id, schedule_id, posting_id, actor_user_id, status, work_start_at, work_end_at, reserved_range, applicant_name, applicant_phone, departure_location,
                               profile_snapshot, tax_identity_id, bank_code, bank_name, account_number_enc, account_holder, message, signed_at, signed_ip, signed_user_agent, signed_terms_version, snapshot_hash,
                               selected_by, selected_at, response_due_at, condition_snapshot, moved_from_application_id, penalty_exempt)
                        SELECT :np, :ns, a.posting_id, a.actor_user_id, :st, COALESCE(np.start_at, :nstart), COALESCE(np.end_at, :nend),
                               CASE WHEN :picked THEN tstzrange(COALESCE(np.start_at, :nstart) - make_interval(mins => :half), COALESCE(np.end_at, :nend) + make_interval(mins => :half)) END,
                               a.applicant_name, a.applicant_phone, a.departure_location, a.profile_snapshot, a.tax_identity_id, a.bank_code, a.bank_name, a.account_number_enc, a.account_holder, a.message,
                               a.signed_at, a.signed_ip, a.signed_user_agent, a.signed_terms_version, a.snapshot_hash,
                               CASE WHEN :picked THEN :by END, CASE WHEN :picked THEN now() END, CASE WHEN :picked THEN LEAST(now() + make_interval(hours => :hours), :nstart - interval '3 hours') END,
                               a.condition_snapshot, a.id, FALSE
                          FROM applications a JOIN job_positions np ON np.id = :np WHERE a.id = :old RETURNING id
                        """).param("np", newPos).param("ns", newSchedule).param("st", wasPicked ? "SELECTED" : "APPLIED").param("nstart", newStart).param("nend", newEnd).param("picked", wasPicked)
                        .param("half", half).param("by", userId).param("hours", hours).param("old", oldApp).query(Long.class).single();
                jdbc.sql("INSERT INTO file_attachments (file_id, entity_type, entity_id, usage, sort_order) SELECT file_id, 'APPLICATION', :n, usage, sort_order FROM file_attachments WHERE entity_type = 'APPLICATION' AND entity_id = :o")
                        .param("n", newApp).param("o", oldApp).update();
                jdbc.sql("UPDATE applications SET status = 'CANCELED_BY_SYSTEM', penalty_exempt = TRUE, canceled_at = now(), cancel_reason = :r, updated_at = now() WHERE id = :id").param("r", "일정 연기").param("id", oldApp).update();
                jdbc.sql("INSERT INTO application_status_histories (application_id, from_status, to_status, changed_by, reason) VALUES (:a, :f, 'CANCELED_BY_SYSTEM', :u, '일정 연기로 새 일정으로 이동')").param("a", oldApp).param("f", oldStatus).param("u", userId).update();
                jdbc.sql("INSERT INTO application_status_histories (application_id, from_status, to_status, changed_by, reason) VALUES (:a, NULL, :t, :u, '일정 연기로 이동됨')").param("a", newApp).param("t", wasPicked ? "SELECTED" : "APPLIED").param("u", userId).update();
                if (wasPicked) {
                    selected++;
                    notifications.notify(((Number) a.get("actorUserId")).longValue(), "POSTPONED", "📅 일정이 연기되었어요", s.get("title") + " — 새 일정을 확인하고 다시 확정해 주세요. 취소해도 불이익은 없어요.", "/me/applications/" + newApp, true, false, Map.of("applicationId", newApp), null);
                } else applied++;
                moved++;
            }
            jdbc.sql("UPDATE job_positions SET selected_count = :s, confirmed_count = 0, applied_count = :a WHERE id = :id").param("s", selected).param("a", applied).param("id", newPos).update();
            jdbc.sql("UPDATE job_positions SET selected_count = 0, confirmed_count = 0, applied_count = 0, status = 'CANCELED', updated_at = now() WHERE id = :id").param("id", oldPos).update();
        }
        jdbc.sql("UPDATE job_schedules SET status = 'POSTPONED', updated_at = now() WHERE id = :id").param("id", scheduleId).update();
        jdbc.sql("UPDATE job_postings SET first_shoot_date = (SELECT min(start_date) FROM job_schedules WHERE posting_id = :p AND status IN ('OPEN','CLOSED')), last_shoot_date = (SELECT max(end_date) FROM job_schedules WHERE posting_id = :p AND status IN ('OPEN','CLOSED')), updated_at = now() WHERE id = :p").param("p", postingId).update();
        audit.record(userId, "SCHEDULE_POSTPONE", "JOB_SCHEDULE", scheduleId, "{\"newScheduleId\":" + newSchedule + "}");
        return Map.of("newScheduleId", newSchedule, "movedApplications", moved);
    }
}
