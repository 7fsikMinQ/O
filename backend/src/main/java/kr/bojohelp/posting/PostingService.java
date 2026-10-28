package kr.bojohelp.posting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.bojohelp.audit.AuditService;
import kr.bojohelp.common.Json;
import kr.bojohelp.common.Rows;
import kr.bojohelp.company.CompanyAccess;
import kr.bojohelp.message.NotificationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 업체의 공고 관리: 작성(임시저장)·수정·게시·마감·마감 해제·취소·복사. 공고 → 일정(1시간~21일) → 역할(정원·필수 요건·시급).
 * 보수는 시급제이며 최저시급은 근무 시작일 연도 값을 쓴다(직접 입력도 그 이상이어야 한다).
 */
@Service
public class PostingService {

    public record PositionReq(
            @NotBlank @Size(max = 100) String title,
            Long categoryId,
            @NotNull @Min(1) @Max(1000) Integer headcount,
            @Pattern(regexp = "ANY|MALE|FEMALE") String genderReq,
            Integer birthYearMin, Integer birthYearMax,
            Integer heightMin, Integer heightMax,
            Integer weightMin, Integer weightMax,
            List<@Pattern(regexp = "SHAVED|SHORT|MEDIUM|LONG") String> hairLengths,
            @Pattern(regexp = "ANY|NONE") String tattooReq,
            @Pattern(regexp = "MIN_WAGE|CUSTOM") String wageMode,
            Integer hourlyWageKrw,
            @Size(max = 1000) String payNote,
            @Size(max = 300) String dressCode,
            @Size(max = 1000) String requirements,
            OffsetDateTime startAt, OffsetDateTime endAt,
            @Size(max = 200) String meetingPlace,
            @Size(max = 300) String meetingAddress) {}

    public record ScheduleReq(
            @NotNull OffsetDateTime startAt,
            @NotNull OffsetDateTime endAt,
            Boolean isLodging,
            Long regionId,
            @Size(max = 255) String shootingLocation,
            @Size(max = 200) String meetingPlace,
            @Size(max = 300) String meetingAddress,
            java.math.BigDecimal latitude, java.math.BigDecimal longitude,
            @Size(max = 1000) String note,
            @NotEmpty @Valid List<PositionReq> positions) {}

    public record PostingReq(
            @NotBlank @Size(max = 200) String title,
            String description,
            Boolean concealDetails,
            @Size(max = 20) String contactPhone,
            OffsetDateTime applyDeadlineAt,
            Boolean isUrgent,
            @NotEmpty @Valid List<ScheduleReq> schedules) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final NotificationService notifications;
    private final PostingQuery query;

    public PostingService(JdbcClient jdbc, AuditService audit, NotificationService notifications, PostingQuery query) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
        this.query = query;
    }

    @Transactional
    public Map<String, Object> create(long userId, CompanyAccess.Membership m, PostingReq r) {
        long id = jdbc.sql("""
                INSERT INTO job_postings (company_id, title, description, conceal_details, contact_phone, apply_deadline_at, is_urgent, created_by)
                VALUES (:c, :t, :d, COALESCE(:conceal, TRUE), :phone, :deadline, COALESCE(:urgent, FALSE), :u) RETURNING id
                """)
                .param("c", m.companyId()).param("t", r.title().trim()).param("d", r.description()).param("conceal", r.concealDetails())
                .param("phone", r.contactPhone()).param("deadline", r.applyDeadlineAt()).param("urgent", r.isUrgent()).param("u", userId)
                .query(Long.class).single();
        writeSchedules(id, r);
        audit.record(userId, "POSTING_CREATE", "JOB_POSTING", id, null);
        return query.forCompany(id);
    }

    @Transactional
    public Map<String, Object> update(long userId, long postingId, PostingReq r) {
        Map<String, Object> p = lock(postingId);
        String status = (String) p.get("status");
        if ("DRAFT".equals(status)) {
            jdbc.sql("""
                    UPDATE job_postings SET title = :t, description = :d, conceal_details = COALESCE(:conceal, conceal_details), contact_phone = :phone,
                           apply_deadline_at = :deadline, is_urgent = COALESCE(:urgent, is_urgent), updated_at = now() WHERE id = :id
                    """)
                    .param("t", r.title().trim()).param("d", r.description()).param("conceal", r.concealDetails()).param("phone", r.contactPhone())
                    .param("deadline", r.applyDeadlineAt()).param("urgent", r.isUrgent()).param("id", postingId).update();
            jdbc.sql("DELETE FROM job_positions WHERE posting_id = :p").param("p", postingId).update();
            jdbc.sql("DELETE FROM job_schedules WHERE posting_id = :p").param("p", postingId).update();
            writeSchedules(postingId, r);
        } else if ("OPEN".equals(status)) {   // 게시 뒤에는 지원서가 걸려 있으므로 구조(일정·역할)는 그대로 두고 안내 문구·마감 시각만 바꾼다
            jdbc.sql("""
                    UPDATE job_postings SET title = :t, description = :d, conceal_details = COALESCE(:conceal, conceal_details), contact_phone = :phone,
                           apply_deadline_at = :deadline, is_urgent = COALESCE(:urgent, is_urgent), updated_at = now() WHERE id = :id
                    """)
                    .param("t", r.title().trim()).param("d", r.description()).param("conceal", r.concealDetails()).param("phone", r.contactPhone())
                    .param("deadline", r.applyDeadlineAt()).param("urgent", r.isUrgent()).param("id", postingId).update();
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 마감되었거나 취소된 공고는 고칠 수 없어요.");
        }
        audit.record(userId, "POSTING_UPDATE", "JOB_POSTING", postingId, null);
        return query.forCompany(postingId);
    }

    /** 게시된 공고의 역할 정원 변경(선발·확정 인원보다 작게는 못 줄인다). */
    @Transactional
    public Map<String, Object> changeHeadcount(long userId, long positionId, int headcount) {
        var row = jdbc.sql("SELECT posting_id, selected_count + confirmed_count FROM job_positions WHERE id = :id FOR UPDATE")
                .param("id", positionId).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (headcount < 1 || headcount > 1000 || headcount < row[1]) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 뽑은 인원(" + row[1] + "명)보다 적게 줄일 수 없어요.");
        jdbc.sql("UPDATE job_positions SET headcount = :h, updated_at = now(), version = version + 1 WHERE id = :id").param("h", headcount).param("id", positionId).update();
        audit.record(userId, "POSITION_HEADCOUNT", "JOB_POSITION", positionId, "{\"headcount\":" + headcount + "}");
        return query.forCompany(row[0]);
    }

    @Transactional
    public Map<String, Object> publish(long userId, long postingId) {
        Map<String, Object> p = lock(postingId);
        if (!"DRAFT".equals(p.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "임시저장 상태의 공고만 게시할 수 있어요.");
        if (p.get("applyDeadlineAt") != null && OffsetDateTime.parse((String) p.get("applyDeadlineAt")).isBefore(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원 마감 시각이 이미 지났어요.");
        }
        int positions = jdbc.sql("SELECT count(*) FROM job_positions WHERE posting_id = :p").param("p", postingId).query(Integer.class).single();
        if (positions == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "역할을 하나 이상 넣어 주세요.");
        long late = jdbc.sql("SELECT count(*) FROM job_schedules WHERE posting_id = :p AND start_at <= now()").param("p", postingId).query(Long.class).single();
        if (late > 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 시작된 일정이 있어요. 날짜를 확인해 주세요.");
        jdbc.sql("UPDATE job_postings SET status = 'OPEN', published_at = now(), updated_at = now() WHERE id = :id").param("id", postingId).update();
        audit.record(userId, "POSTING_PUBLISH", "JOB_POSTING", postingId, null);
        announceToStars(postingId, (Long) p.get("companyId"), (String) p.get("title"));
        return query.forCompany(postingId);
    }

    /** 마감: 통보 없이 남은 지원(APPLIED)은 자동 불합격. 합격 통보(SELECTED)는 출연자 응답을 계속 기다린다. 마감 뒤에도 재선발은 가능(SelectionService). */
    @Transactional
    public Map<String, Object> close(long userId, long postingId, String why) {
        Map<String, Object> p = lock(postingId);
        if (!"OPEN".equals(p.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "게시 중인 공고만 마감할 수 있어요.");
        closeInternal(postingId, userId, why);
        return query.forCompany(postingId);
    }

    /** 지원 마감 시각 경과 등 시스템 마감(userId=null). */
    @Transactional
    public void closeBySystem(long postingId) {
        Map<String, Object> p = lock(postingId);
        if ("OPEN".equals(p.get("status"))) closeInternal(postingId, null, "SYSTEM_DEADLINE");
    }

    private void closeInternal(long postingId, Long userId, String why) {
        jdbc.sql("UPDATE job_postings SET status = 'CLOSED', closed_at = now(), updated_at = now() WHERE id = :id").param("id", postingId).update();
        jdbc.sql("UPDATE job_schedules SET status = 'CLOSED', updated_at = now() WHERE posting_id = :id AND status = 'OPEN'").param("id", postingId).update();
        boolean auto = "true".equals(jdbc.sql("SELECT value FROM app_settings WHERE key = 'auto_reject_on_close'").query(String.class).optional().orElse("true"));
        if (auto) {
            List<Map<String, Object>> apps = jdbc.sql("SELECT id, actor_user_id, position_id FROM applications WHERE posting_id = :p AND status = 'APPLIED' FOR UPDATE")
                    .param("p", postingId).query(Rows.camel()).list();
            for (Map<String, Object> a : apps) {
                long appId = ((Number) a.get("id")).longValue();
                jdbc.sql("UPDATE applications SET status = 'AUTO_REJECTED', updated_at = now() WHERE id = :id").param("id", appId).update();
                jdbc.sql("INSERT INTO application_status_histories (application_id, from_status, to_status, changed_by, reason) VALUES (:a, 'APPLIED', 'AUTO_REJECTED', :u, :r)")
                        .param("a", appId).param("u", userId).param("r", why).update();
                notifications.notify(((Number) a.get("actorUserId")).longValue(), "APPLICATION_AUTO_REJECTED", "이번에는 함께하지 못해요", "지원하신 공고가 마감되었어요.", "/me/applications/" + appId);
            }
        }
        audit.record(userId, "POSTING_CLOSE", "JOB_POSTING", postingId, null);
    }

    /** 마감 해제: 정원이 남은 역할이 있고 지원 마감·일정 시작 전일 때만. 자동 불합격된 지원은 되살리지 않는다(필요하면 재선발). */
    @Transactional
    public Map<String, Object> reopen(long userId, long postingId) {
        Map<String, Object> p = lock(postingId);
        if (!"CLOSED".equals(p.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT);
        long room = jdbc.sql("SELECT count(*) FROM job_positions pos JOIN job_schedules s ON s.id = pos.schedule_id WHERE pos.posting_id = :p AND pos.status = 'OPEN' AND pos.selected_count + pos.confirmed_count < pos.headcount AND s.start_at > now() AND s.status IN ('OPEN','CLOSED')")
                .param("p", postingId).query(Long.class).single();
        if (room == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "정원이 남은 역할이 없거나 일정이 이미 시작됐어요.");
        jdbc.sql("UPDATE job_postings SET status = 'OPEN', closed_at = NULL, updated_at = now() WHERE id = :id").param("id", postingId).update();
        jdbc.sql("UPDATE job_schedules SET status = 'OPEN', updated_at = now() WHERE posting_id = :id AND status = 'CLOSED' AND start_at > now()").param("id", postingId).update();
        audit.record(userId, "POSTING_REOPEN", "JOB_POSTING", postingId, null);
        return query.forCompany(postingId);
    }

    /** 공고 취소(대표·팀장): 합격 통보·확정 인원은 패널티 없이 취소 처리되고 출연자에게 알린다. */
    @Transactional
    public Map<String, Object> cancel(long userId, long postingId, String reason) {
        Map<String, Object> p = lock(postingId);
        String status = (String) p.get("status");
        if ("CANCELED".equals(status) || "COMPLETED".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        jdbc.sql("UPDATE job_postings SET status = 'CANCELED', closed_at = COALESCE(closed_at, now()), updated_at = now() WHERE id = :id").param("id", postingId).update();
        jdbc.sql("UPDATE job_schedules SET status = 'CANCELED', updated_at = now() WHERE posting_id = :id AND status IN ('OPEN','CLOSED')").param("id", postingId).update();
        jdbc.sql("UPDATE job_positions SET status = 'CANCELED', updated_at = now() WHERE posting_id = :id AND status IN ('OPEN','CLOSED')").param("id", postingId).update();
        List<Map<String, Object>> apps = jdbc.sql("SELECT id, actor_user_id, status FROM applications WHERE posting_id = :p AND status IN ('APPLIED','SELECTED','CONFIRMED') FOR UPDATE")
                .param("p", postingId).query(Rows.camel()).list();
        for (Map<String, Object> a : apps) {
            long appId = ((Number) a.get("id")).longValue();
            jdbc.sql("UPDATE applications SET status = 'CANCELED_BY_COMPANY', penalty_exempt = TRUE, canceled_at = now(), cancel_reason = :r, updated_at = now() WHERE id = :id")
                    .param("r", reason).param("id", appId).update();
            jdbc.sql("INSERT INTO application_status_histories (application_id, from_status, to_status, changed_by, reason) VALUES (:a, :f, 'CANCELED_BY_COMPANY', :u, :r)")
                    .param("a", appId).param("f", a.get("status")).param("u", userId).param("r", reason).update();
            notifications.notify(((Number) a.get("actorUserId")).longValue(), "POSTING_CANCELED", "공고가 취소되었어요", "업체 사정으로 공고가 취소되었어요. 불이익은 없어요.", "/me/applications/" + appId, true, false, null, null);
        }
        jdbc.sql("UPDATE job_positions SET selected_count = 0, confirmed_count = 0, updated_at = now() WHERE posting_id = :p").param("p", postingId).update();
        audit.record(userId, "POSTING_CANCEL", "JOB_POSTING", postingId, null);
        return query.forCompany(postingId);
    }

    /** 복사: 같은 내용으로 임시저장 공고를 새로 만든다. shiftDays 만큼 날짜를 뒤로 민다(기본 0). */
    @Transactional
    public Map<String, Object> copy(long userId, long postingId, int shiftDays) {
        Map<String, Object> src = query.forCompany(postingId);
        List<ScheduleReq> schedules = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) src.get("schedules")) {
            List<PositionReq> ps = new ArrayList<>();
            for (Map<String, Object> p : (List<Map<String, Object>>) s.get("positions")) {
                ps.add(new PositionReq((String) p.get("title"), p.get("categoryId") == null ? null : ((Number) p.get("categoryId")).longValue(), ((Number) p.get("headcount")).intValue(),
                        (String) p.get("genderReq"), intOrNull(p.get("birthYearMin")), intOrNull(p.get("birthYearMax")), intOrNull(p.get("heightMin")), intOrNull(p.get("heightMax")),
                        intOrNull(p.get("weightMin")), intOrNull(p.get("weightMax")), (List<String>) p.get("hairLengths"), (String) p.get("tattooReq"),
                        (String) p.get("wageMode"), intOrNull(p.get("hourlyWageKrw")), (String) p.get("payNote"), (String) p.get("dressCode"), (String) p.get("requirements"),
                        shift((String) p.get("startAt"), shiftDays), shift((String) p.get("endAt"), shiftDays), (String) p.get("meetingPlace"), (String) p.get("meetingAddress")));
            }
            schedules.add(new ScheduleReq(shift((String) s.get("startAt"), shiftDays), shift((String) s.get("endAt"), shiftDays), (Boolean) s.get("isLodging"),
                    s.get("regionId") == null ? null : ((Number) s.get("regionId")).longValue(), (String) s.get("shootingLocation"), (String) s.get("meetingPlace"),
                    (String) s.get("meetingAddress"), (java.math.BigDecimal) s.get("latitude"), (java.math.BigDecimal) s.get("longitude"), (String) s.get("note"), ps));
        }
        long companyId = ((Number) src.get("companyId")).longValue();
        PostingReq req = new PostingReq((String) src.get("title"), (String) src.get("description"), (Boolean) src.get("concealDetails"), (String) src.get("contactPhone"), null, false, schedules);
        return create(userId, new CompanyAccess.Membership(companyId, "OWNER", "APPROVED"), req);
    }

    // ----- 내부 -----

    private static Integer intOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static OffsetDateTime shift(String iso, int days) {
        return iso == null ? null : OffsetDateTime.parse(iso).plusDays(days);
    }

    /** 공고 행을 잠그고(동시 변경 방지) 요약을 돌려준다. */
    Map<String, Object> lock(long postingId) {
        return jdbc.sql("SELECT id, company_id, status, title, apply_deadline_at FROM job_postings WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
                .param("id", postingId).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void writeSchedules(long postingId, PostingReq r) {
        for (ScheduleReq s : r.schedules()) {
            Duration len = Duration.between(s.startAt(), s.endAt());
            if (len.compareTo(Duration.ofHours(1)) < 0 || len.compareTo(Duration.ofDays(21)) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "일정은 1시간 이상 21일(3주) 이하로 정해 주세요.");
            }
            long scheduleId;
            try {
                scheduleId = jdbc.sql("""
                        INSERT INTO job_schedules (posting_id, start_at, end_at, is_lodging, region_id, shooting_location, meeting_place, meeting_address, latitude, longitude, note)
                        VALUES (:p, :s, :e, COALESCE(:lodging, FALSE), :region, :loc, :place, :addr, :lat, :lng, :note) RETURNING id
                        """)
                        .param("p", postingId).param("s", s.startAt()).param("e", s.endAt()).param("lodging", s.isLodging()).param("region", s.regionId())
                        .param("loc", s.shootingLocation()).param("place", s.meetingPlace()).param("addr", s.meetingAddress()).param("lat", s.latitude())
                        .param("lng", s.longitude()).param("note", s.note()).query(Long.class).single();
            } catch (DataIntegrityViolationException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "일정 값을 확인해 주세요.");
            }
            for (PositionReq pos : s.positions()) writePosition(postingId, scheduleId, s, pos);
        }
        jdbc.sql("UPDATE job_postings SET first_shoot_date = (SELECT min(start_date) FROM job_schedules WHERE posting_id = :p), last_shoot_date = (SELECT max(end_date) FROM job_schedules WHERE posting_id = :p) WHERE id = :p")
                .param("p", postingId).update();
    }

    private void writePosition(long postingId, long scheduleId, ScheduleReq s, PositionReq p) {
        OffsetDateTime workStart = p.startAt() != null ? p.startAt() : s.startAt();
        int year = WageRules.year(workStart);
        int minWage = jdbc.sql("SELECT hourly_krw FROM minimum_wages WHERE year = :y").param("y", year).query(Integer.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, year + "년 최저시급이 아직 등록되지 않았어요. 관리자에게 문의해 주세요."));
        String mode = p.wageMode() == null ? "MIN_WAGE" : p.wageMode();
        int hourly;
        if ("MIN_WAGE".equals(mode)) hourly = minWage;
        else {
            if (p.hourlyWageKrw() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시급을 입력해 주세요.");
            if (p.hourlyWageKrw() < minWage) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시급은 " + year + "년 최저시급 " + String.format("%,d", minWage) + "원 이상으로 입력해 주세요.");
            hourly = p.hourlyWageKrw();
        }
        if ((p.startAt() == null) != (p.endAt() == null)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "역할별 시간은 시작과 끝을 함께 넣어 주세요.");
        try {
            jdbc.sql("""
                    INSERT INTO job_positions (schedule_id, posting_id, category_id, title, start_at, end_at, meeting_place, meeting_address, headcount,
                        gender_req, birth_year_min, birth_year_max, height_min, height_max, weight_min, weight_max, hair_lengths, tattoo_req,
                        wage_mode, hourly_wage_krw, min_wage_year, pay_note, dress_code, requirements)
                    VALUES (:sch, :post, :cat, :title, :s, :e, :place, :addr, :hc,
                        COALESCE(:gender, 'ANY'), :bmin, :bmax, :hmin, :hmax, :wmin, :wmax, CAST(:hair AS jsonb), COALESCE(:tattoo, 'ANY'),
                        :mode, :hourly, :year, :note, :dress, :req)
                    """)
                    .param("sch", scheduleId).param("post", postingId).param("cat", p.categoryId()).param("title", p.title().trim()).param("s", p.startAt()).param("e", p.endAt())
                    .param("place", p.meetingPlace()).param("addr", p.meetingAddress()).param("hc", p.headcount()).param("gender", p.genderReq())
                    .param("bmin", p.birthYearMin()).param("bmax", p.birthYearMax()).param("hmin", p.heightMin()).param("hmax", p.heightMax())
                    .param("wmin", p.weightMin()).param("wmax", p.weightMax())
                    .param("hair", p.hairLengths() == null || p.hairLengths().isEmpty() ? null : Json.write(p.hairLengths())).param("tattoo", p.tattooReq())
                    .param("mode", mode).param("hourly", hourly).param("year", "MIN_WAGE".equals(mode) ? year : null).param("note", p.payNote())
                    .param("dress", p.dressCode()).param("req", p.requirements()).update();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "역할 값을 확인해 주세요(범위·시간·분류).");
        }
    }

    /** ⭐ 출연자에게 새 공고를 먼저 알린다(끈 사람 제외). 일반 알림은 우선 시간 뒤에 아웃박스 워커가 처리한다. */
    private void announceToStars(long postingId, long companyId, String title) {
        List<Long> stars = jdbc.sql("""
                SELECT st.actor_user_id FROM company_starred_actors st JOIN actor_profiles ap ON ap.user_id = st.actor_user_id
                 WHERE st.company_id = :c AND ap.allow_star_alerts
                """).param("c", companyId).query(Long.class).list();
        for (Long a : stars) {
            notifications.notify(a, "NEW_POSTING_STAR", "⭐ 새 공고가 올라왔어요", title, "/jobs/" + postingId);
        }
        Integer minutes = jdbc.sql("SELECT star_head_start_minutes FROM job_postings WHERE id = :id").param("id", postingId).query(Integer.class).optional().orElse(null);
        int head = minutes != null ? minutes : Integer.parseInt(jdbc.sql("SELECT value FROM app_settings WHERE key = 'star_head_start_minutes'").query(String.class).optional().orElse("1"));
        jdbc.sql("INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload, available_at) VALUES ('JOB_POSTING', :id, 'NEW_POSTING_ALERT', CAST(:p AS jsonb), now() + make_interval(mins => :m))")
                .param("id", postingId).param("p", Json.write(Map.of("postingId", postingId))).param("m", head).update();
    }
}
