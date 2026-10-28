package kr.bojohelp.posting;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.bojohelp.common.Rows;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 공고 조회: 업체 편집용 상세, 출연자용 목록·검색·달력·상세(비공개 현장 규칙 적용). 읽기 전용. */
@Service
public class PostingQuery {

    private final JdbcClient jdbc;

    public PostingQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ===== 업체 =====

    public Map<String, Object> forCompany(long postingId) {
        Map<String, Object> p = jdbc.sql("""
                SELECT p.id, p.company_id, c.name AS company_name, p.title, p.description, p.status, p.is_urgent, p.conceal_details, p.contact_phone,
                       p.apply_deadline_at, p.first_shoot_date, p.last_shoot_date, p.published_at, p.closed_at, p.created_at
                  FROM job_postings p JOIN companies c ON c.id = p.company_id WHERE p.id = :id AND p.deleted_at IS NULL
                """).param("id", postingId).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        p.put("schedules", schedules(postingId, true, true));
        p.put("timeNotice", timeNotice());
        return p;
    }

    public long companyOf(long postingId) {
        return jdbc.sql("SELECT company_id FROM job_postings WHERE id = :id AND deleted_at IS NULL").param("id", postingId).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public long companyOfPosition(long positionId) {
        return jdbc.sql("SELECT p.company_id FROM job_positions pos JOIN job_postings p ON p.id = pos.posting_id WHERE pos.id = :id").param("id", positionId).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public long companyOfSchedule(long scheduleId) {
        return jdbc.sql("SELECT p.company_id FROM job_schedules s JOIN job_postings p ON p.id = s.posting_id WHERE s.id = :id").param("id", scheduleId).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public List<Map<String, Object>> companyList(long companyId, String status) {
        return jdbc.sql("""
                SELECT p.id, p.title, p.status, p.first_shoot_date, p.last_shoot_date, p.apply_deadline_at, p.published_at,
                       COALESCE(sum(pos.headcount), 0) AS headcount, COALESCE(sum(pos.applied_count), 0) AS applied_count,
                       COALESCE(sum(pos.selected_count), 0) AS selected_count, COALESCE(sum(pos.confirmed_count), 0) AS confirmed_count,
                       (SELECT count(*) FROM applications a WHERE a.posting_id = p.id AND a.status = 'APPLIED') AS waiting_count
                  FROM job_postings p LEFT JOIN job_positions pos ON pos.posting_id = p.id
                 WHERE p.company_id = :c AND p.deleted_at IS NULL AND (CAST(:s AS text) IS NULL OR p.status = :s)
                 GROUP BY p.id ORDER BY p.created_at DESC, p.id DESC LIMIT 200
                """).param("c", companyId).param("s", status).query(Rows.camel()).list();
    }

    public Map<String, Object> dashboard(long companyId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("postingsByStatus", jdbc.sql("SELECT status, count(*) AS n FROM job_postings WHERE company_id = :c AND deleted_at IS NULL GROUP BY status").param("c", companyId).query(Rows.camel()).list());
        m.put("waitingApplicants", jdbc.sql("SELECT count(*) FROM applications a JOIN job_postings p ON p.id = a.posting_id WHERE p.company_id = :c AND a.status = 'APPLIED'").param("c", companyId).query(Integer.class).single());
        m.put("awaitingActorResponse", jdbc.sql("SELECT count(*) FROM applications a JOIN job_postings p ON p.id = a.posting_id WHERE p.company_id = :c AND a.status = 'SELECTED'").param("c", companyId).query(Integer.class).single());
        m.put("pendingCancelReviews", jdbc.sql("SELECT count(*) FROM cancellation_reviews WHERE company_id = :c AND status = 'PENDING'").param("c", companyId).query(Integer.class).single());
        m.put("unresolvedConflicts", jdbc.sql("""
                SELECT count(*) FROM schedule_conflicts sc JOIN applications a ON a.id IN (sc.application_a_id, sc.application_b_id) JOIN job_postings p ON p.id = a.posting_id
                 WHERE p.company_id = :c AND sc.resolved_at IS NULL
                """).param("c", companyId).query(Integer.class).single());
        return m;
    }

    // ===== 출연자 =====

    /**
     * 공고 목록. 모집 중(OPEN)·지원 마감 전·일정이 끝나지 않은 공고만, 내가 차단한 업체는 제외. 커서는 "yyyy-MM-dd:id".
     */
    public Map<String, Object> search(Long actorUserId, LocalDate from, LocalDate to, Long regionId, String q, String cursor, int limit) {
        int n = Math.min(Math.max(limit, 1), 50);
        LocalDate cursorDate = null;
        long cursorId = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = cursor.split(":");
                cursorDate = LocalDate.parse(parts[0]);
                cursorId = Long.parseLong(parts[1]);
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        }
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT p.id, p.title, c.name AS company_name, p.conceal_details, p.is_urgent, p.first_shoot_date, p.last_shoot_date, p.apply_deadline_at
                  FROM job_postings p JOIN companies c ON c.id = p.company_id
                 WHERE p.status = 'OPEN' AND p.deleted_at IS NULL AND c.status = 'APPROVED' AND c.deleted_at IS NULL
                   AND (p.apply_deadline_at IS NULL OR p.apply_deadline_at > now())
                   AND p.last_shoot_date >= (now() AT TIME ZONE 'Asia/Seoul')::date
                   AND (CAST(:from AS date) IS NULL OR p.last_shoot_date >= :from)
                   AND (CAST(:to AS date) IS NULL OR p.first_shoot_date <= :to)
                   AND (CAST(:region AS bigint) IS NULL OR EXISTS (SELECT 1 FROM job_schedules s WHERE s.posting_id = p.id AND s.region_id = :region))
                   AND (CAST(:q AS text) IS NULL OR p.title ILIKE '%' || :q || '%')
                   AND (CAST(:actor AS bigint) IS NULL OR NOT EXISTS (SELECT 1 FROM actor_company_blocks b WHERE b.actor_user_id = :actor AND b.company_id = p.company_id))
                   AND (CAST(:cd AS date) IS NULL OR (p.first_shoot_date, p.id) > (CAST(:cd AS date), CAST(:cid AS bigint)))
                 ORDER BY p.first_shoot_date, p.id LIMIT :n
                """)
                .param("from", from).param("to", to).param("region", regionId).param("q", q == null || q.isBlank() ? null : q.trim())
                .param("actor", actorUserId).param("cd", cursorDate).param("cid", cursorId).param("n", n + 1).query(Rows.camel()).list();
        boolean more = rows.size() > n;
        List<Map<String, Object>> page = more ? rows.subList(0, n) : rows;
        for (Map<String, Object> p : page) {
            long id = ((Number) p.get("id")).longValue();
            p.put("schedules", schedules(id, false, false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", page);
        out.put("nextCursor", more ? page.get(page.size() - 1).get("firstShootDate") + ":" + page.get(page.size() - 1).get("id") : null);
        out.put("timeNotice", timeNotice());
        return out;
    }

    /** 월 달력: 날짜별 모집 중 공고 수. month = yyyy-MM. */
    public List<Map<String, Object>> calendar(String month, Long regionId) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return jdbc.sql("""
                SELECT d::date AS date, count(DISTINCT s.posting_id) AS count
                  FROM generate_series(CAST(:first AS date), CAST(:last AS date), interval '1 day') d
                  JOIN job_schedules s ON d::date BETWEEN s.start_date AND s.end_date AND s.status = 'OPEN'
                  JOIN job_postings p ON p.id = s.posting_id AND p.status = 'OPEN' AND p.deleted_at IS NULL AND (p.apply_deadline_at IS NULL OR p.apply_deadline_at > now())
                 WHERE (CAST(:region AS bigint) IS NULL OR s.region_id = :region)
                 GROUP BY d ORDER BY d
                """).param("first", ym.atDay(1)).param("last", ym.atEndOfMonth()).param("region", regionId).query(Rows.camel()).list();
    }

    /**
     * 공고 상세(출연자). 비공개 현장(conceal_details)이면 합격 통보를 받은 사람에게만 촬영지·집합지·담당 연락처를 보여 준다.
     * 모집 중이 아니어도 내가 지원한 공고는 볼 수 있다.
     */
    public Map<String, Object> detailForActor(long postingId, Long actorUserId) {
        Map<String, Object> p = jdbc.sql("""
                SELECT p.id, p.company_id, c.name AS company_name, p.title, p.description, p.status, p.is_urgent, p.conceal_details, p.contact_phone,
                       p.apply_deadline_at, p.first_shoot_date, p.last_shoot_date
                  FROM job_postings p JOIN companies c ON c.id = p.company_id WHERE p.id = :id AND p.deleted_at IS NULL AND c.status = 'APPROVED'
                """).param("id", postingId).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean mine = actorUserId != null && jdbc.sql("SELECT count(*) FROM applications WHERE posting_id = :p AND actor_user_id = :u").param("p", postingId).param("u", actorUserId).query(Integer.class).single() > 0;
        if (!"OPEN".equals(p.get("status")) && !mine) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        boolean revealed = actorUserId != null && jdbc.sql("SELECT count(*) FROM applications WHERE posting_id = :p AND actor_user_id = :u AND status IN ('SELECTED','CONFIRMED')").param("p", postingId).param("u", actorUserId).query(Integer.class).single() > 0;
        boolean hide = Boolean.TRUE.equals(p.get("concealDetails")) && !revealed;
        p.put("detailsHidden", hide);
        if (hide) p.put("contactPhone", null);
        List<Map<String, Object>> schedules = schedules(postingId, false, true);
        for (Map<String, Object> s : schedules) {
            if (hide) {
                s.put("shootingLocation", null);
                s.put("meetingPlace", null);
                s.put("meetingAddress", null);
                s.put("latitude", null);
                s.put("longitude", null);
                for (Map<String, Object> pos : (List<Map<String, Object>>) s.get("positions")) {
                    pos.put("meetingPlace", null);
                    pos.put("meetingAddress", null);
                }
            }
            if (actorUserId != null) {
                for (Map<String, Object> pos : (List<Map<String, Object>>) s.get("positions")) {
                    pos.put("myApplication", jdbc.sql("SELECT id, status FROM applications WHERE position_id = :pos AND actor_user_id = :u ORDER BY id DESC LIMIT 1")
                            .param("pos", pos.get("id")).param("u", actorUserId).query(Rows.camel()).optional().orElse(null));
                }
            }
        }
        p.put("schedules", schedules);
        p.put("timeNotice", timeNotice());
        return p;
    }

    // ===== 공통 =====

    /** 일정과 역할(중첩). internal=true 면 업체 편집용으로 모든 필드를, false 면 목록용 요약만. */
    List<Map<String, Object>> schedules(long postingId, boolean internal, boolean withPositions) {
        List<Map<String, Object>> list = jdbc.sql("""
                SELECT s.id, s.start_at, s.end_at, s.start_date, s.end_date, s.is_lodging, s.region_id, r.name AS region_name, s.shooting_location, s.meeting_place,
                       s.meeting_address, s.latitude, s.longitude, s.note, s.status, s.gather_tolerance_minutes, s.end_tolerance_minutes, s.postponed_from_schedule_id
                  FROM job_schedules s LEFT JOIN regions r ON r.id = s.region_id WHERE s.posting_id = :p ORDER BY s.start_at, s.id
                """).param("p", postingId).query(Rows.camel()).list();
        if (!internal && !withPositions) {   // 목록 요약: 현장 비공개 항목은 빼고 시간·지역·잔여 인원만
            for (Map<String, Object> s : list) {
                s.remove("shootingLocation");
                s.remove("meetingPlace");
                s.remove("meetingAddress");
                s.remove("latitude");
                s.remove("longitude");
                s.remove("note");
            }
        }
        int threshold = Integer.parseInt(setting("fixed_pay_threshold_hours", "9"));
        int minPay = Integer.parseInt(setting("min_pay_hours", "8"));
        for (Map<String, Object> s : list) {
            long sid = ((Number) s.get("id")).longValue();
            List<Map<String, Object>> positions = jdbc.sql("""
                    SELECT id, title, category_id, headcount, selected_count, confirmed_count, applied_count, headcount - selected_count - confirmed_count AS remaining,
                           start_at, end_at, meeting_place, meeting_address, gender_req, birth_year_min, birth_year_max, height_min, height_max, weight_min, weight_max,
                           hair_lengths, tattoo_req, wage_mode, hourly_wage_krw, min_wage_year, pay_note, dress_code, requirements, status
                      FROM job_positions WHERE schedule_id = :s ORDER BY id
                    """).param("s", sid).query(Rows.camel()).list();
            OffsetDateTime ss = OffsetDateTime.parse((String) s.get("startAt"));
            OffsetDateTime se = OffsetDateTime.parse((String) s.get("endAt"));
            for (Map<String, Object> pos : positions) {
                OffsetDateTime ps = pos.get("startAt") == null ? ss : OffsetDateTime.parse((String) pos.get("startAt"));
                OffsetDateTime pe = pos.get("endAt") == null ? se : OffsetDateTime.parse((String) pos.get("endAt"));
                pos.put("estimatedPayKrw", WageRules.estimate(((Number) pos.get("hourlyWageKrw")).intValue(), ps, pe, threshold, minPay));
                pos.put("workStartAt", ps.toInstant().toString());
                pos.put("workEndAt", pe.toInstant().toString());
                if (!internal) {
                    pos.remove("appliedCount");
                }
            }
            if (withPositions) s.put("positions", positions);
            else s.put("positions", positions.stream().map(pos -> {
                Map<String, Object> mini = new LinkedHashMap<>();
                mini.put("id", pos.get("id"));
                mini.put("title", pos.get("title"));
                mini.put("remaining", pos.get("remaining"));
                mini.put("headcount", pos.get("headcount"));
                mini.put("estimatedPayKrw", pos.get("estimatedPayKrw"));
                mini.put("hourlyWageKrw", pos.get("hourlyWageKrw"));
                mini.put("wageMode", pos.get("wageMode"));
                return mini;
            }).toList());
        }
        return list;
    }

    private String setting(String key, String def) {
        return jdbc.sql("SELECT value FROM app_settings WHERE key = :k").param("k", key).query(String.class).optional().orElse(def);
    }

    public String timeNotice() {
        return setting("time_estimate_notice", "시간은 모두 예상이며 확정이 아니에요.");
    }
}
