package kr.bojohelp.operation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import kr.bojohelp.common.Rows;
import kr.bojohelp.message.NotificationService;
import kr.bojohelp.posting.WageRules;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 참석 확인과 QR 출퇴근. 업체 구성원(스태프 포함)이 출근/퇴근 세션을 열어야 출연자 앱에 개인 QR 이 나타나고(대리 출석 방지),
 * 토큰은 지원×세션×30초 창을 세션 비밀(nonce)로 HMAC 서명한 값이다. 같은 토큰을 다시 스캔해도 기록은 한 번만 남는다.
 */
@Service
public class AttendanceService {

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final SecureRandom random = new SecureRandom();

    public AttendanceService(JdbcClient jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    private int setting(String key, int def) {
        return Integer.parseInt(jdbc.sql("SELECT value FROM app_settings WHERE key = :k").param("k", key).query(String.class).optional().orElse(String.valueOf(def)));
    }

    // ===== 세션 =====

    @Transactional
    public Map<String, Object> openSession(long userId, long scheduleId, String kind, LocalDate workDate) {
        Map<String, Object> s = jdbc.sql("SELECT status, start_date, end_date FROM job_schedules WHERE id = :s").param("s", scheduleId).query(Rows.camel()).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("CANCELED".equals(s.get("status")) || "POSTPONED".equals(s.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "취소·연기된 일정이에요.");
        LocalDate date = workDate != null ? workDate : LocalDate.now(WageRules.KST);
        if (date.isBefore(LocalDate.parse((String) s.get("startDate"))) || date.isAfter(LocalDate.parse((String) s.get("endDate")))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "근무일이 아니에요.");
        var existing = jdbc.sql("SELECT id FROM attendance_sessions WHERE schedule_id = :s AND work_date = :d AND kind = :k AND closed_at IS NULL").param("s", scheduleId).param("d", date).param("k", kind).query(Long.class).optional();
        if (existing.isPresent()) return Map.of("sessionId", existing.get(), "kind", kind, "workDate", date.toString(), "alreadyOpen", true);
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        long id = jdbc.sql("INSERT INTO attendance_sessions (schedule_id, work_date, kind, opened_by, nonce) VALUES (:s, :d, :k, :u, :n) RETURNING id")
                .param("s", scheduleId).param("d", date).param("k", kind).param("u", userId).param("n", HexFormat.of().formatHex(nonce)).query(Long.class).single();
        return Map.of("sessionId", id, "kind", kind, "workDate", date.toString(), "alreadyOpen", false);
    }

    public long scheduleOfSession(long sessionId) {
        return jdbc.sql("SELECT schedule_id FROM attendance_sessions WHERE id = :id").param("id", sessionId).query(Long.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public void closeSession(long sessionId) {
        jdbc.sql("UPDATE attendance_sessions SET closed_at = now() WHERE id = :id AND closed_at IS NULL").param("id", sessionId).update();
    }

    public List<Map<String, Object>> sessions(long scheduleId) {
        return jdbc.sql("SELECT id, work_date, kind, opened_at, closed_at FROM attendance_sessions WHERE schedule_id = :s ORDER BY id DESC LIMIT 50").param("s", scheduleId).query(Rows.camel()).list();
    }

    // ===== 출연자 QR =====

    public Map<String, Object> qr(long actorUserId, long applicationId) {
        Map<String, Object> a = jdbc.sql("SELECT schedule_id, status FROM applications WHERE id = :id AND actor_user_id = :u").param("id", applicationId).param("u", actorUserId).query(Rows.camel()).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"CONFIRMED".equals(a.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "확정한 일정만 QR 을 쓸 수 있어요.");
        // 퇴근 세션이 열려 있으면 퇴근을, 아니면 출근 세션을 쓴다
        Map<String, Object> s = jdbc.sql("""
                SELECT id, kind, nonce, work_date FROM attendance_sessions WHERE schedule_id = :s AND closed_at IS NULL
                 ORDER BY CASE kind WHEN 'CHECKOUT' THEN 0 ELSE 1 END LIMIT 1
                """).param("s", a.get("scheduleId")).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "아직 출퇴근이 열리지 않았어요. 현장 담당자가 열면 QR 이 나타나요."));
        int rotate = setting("qr_rotate_seconds", 30);
        long window = Instant.now().getEpochSecond() / rotate;
        long sessionId = ((Number) s.get("id")).longValue();
        String payload = applicationId + "." + sessionId + "." + window;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", payload + "." + sign((String) s.get("nonce"), payload));
        m.put("expiresInSeconds", rotate - (Instant.now().getEpochSecond() % rotate));
        m.put("kind", s.get("kind"));
        m.put("workDate", s.get("workDate"));
        return m;
    }

    // ===== 업체: 스캔 =====

    @Transactional
    public Map<String, Object> scan(long scannerUserId, String token, java.util.function.LongConsumer companyCheck) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 4) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QR 을 읽을 수 없어요.");
        long appId, sessionId, window;
        try {
            appId = Long.parseLong(parts[0]);
            sessionId = Long.parseLong(parts[1]);
            window = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QR 을 읽을 수 없어요.");
        }
        Map<String, Object> s = jdbc.sql("SELECT id, schedule_id, work_date, kind, nonce, closed_at FROM attendance_sessions WHERE id = :id").param("id", sessionId).query(Rows.camel()).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "QR 을 읽을 수 없어요."));
        long scheduleId = ((Number) s.get("scheduleId")).longValue();
        long companyId = jdbc.sql("SELECT p.company_id FROM job_schedules sc JOIN job_postings p ON p.id = sc.posting_id WHERE sc.id = :s").param("s", scheduleId).query(Long.class).single();
        companyCheck.accept(companyId);   // 이 업체 구성원이 아니면 404 (호출 쪽에서 CompanyAccess)
        if (s.get("closedAt") != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 닫힌 출퇴근이에요.");
        String payload = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(sign((String) s.get("nonce"), payload).getBytes(StandardCharsets.UTF_8), parts[3].getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QR 이 올바르지 않아요.");
        }
        int rotate = setting("qr_rotate_seconds", 30);
        long now = Instant.now().getEpochSecond() / rotate;
        if (window > now || now - window > 1) throw new ResponseStatusException(HttpStatus.GONE, "QR 이 만료됐어요. 출연자 화면의 새 QR 을 읽어 주세요.");

        Map<String, Object> a = jdbc.sql("SELECT a.id, a.status, a.schedule_id, a.applicant_name, a.work_start_at, a.work_end_at, a.actor_user_id, pos.title AS position_title FROM applications a JOIN job_positions pos ON pos.id = a.position_id WHERE a.id = :id FOR UPDATE OF a")
                .param("id", appId).query((rs, n) -> {
                    Map<String, Object> m = Rows.camel().mapRow(rs, n);
                    m.put("ws", rs.getObject("work_start_at", OffsetDateTime.class));
                    m.put("we", rs.getObject("work_end_at", OffsetDateTime.class));
                    return m;
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
        if (((Number) a.get("scheduleId")).longValue() != scheduleId || !"CONFIRMED".equals(a.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "이 일정의 확정 인원이 아니에요.");

        LocalDate workDate = LocalDate.parse((String) s.get("workDate"));
        String kind = (String) s.get("kind");
        jdbc.sql("INSERT INTO attendances (application_id, work_date) VALUES (:a, :d) ON CONFLICT (application_id, work_date) DO NOTHING").param("a", appId).param("d", workDate).update();
        Map<String, Object> att = jdbc.sql("SELECT id, checkin_at, checkout_at, result FROM attendances WHERE application_id = :a AND work_date = :d FOR UPDATE").param("a", appId).param("d", workDate).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("checkin", rs.getObject("checkin_at", OffsetDateTime.class));
            m.put("checkout", rs.getObject("checkout_at", OffsetDateTime.class));
            m.put("result", rs.getString("result"));
            return m;
        }).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applicationId", appId);
        out.put("name", a.get("applicantName"));
        out.put("position", a.get("positionTitle"));
        out.put("kind", kind);
        if ("CHECKIN".equals(kind)) {
            if (att.get("checkin") != null) {
                out.put("alreadyRecorded", true);
                return out;
            }
            OffsetDateTime ws = (OffsetDateTime) a.get("ws");
            boolean late = OffsetDateTime.now().isAfter(ws.plusMinutes(10));
            jdbc.sql("UPDATE attendances SET checkin_at = now(), checkin_session_id = :s, method_in = 'QR', recorded_by = :u, result = :r, updated_at = now() WHERE id = :id")
                    .param("s", sessionId).param("u", scannerUserId).param("r", late ? "LATE" : "ATTENDED").param("id", att.get("id")).update();
            jdbc.sql("UPDATE actor_profiles SET attended_count = attended_count + 1, updated_at = now() WHERE user_id = :u").param("u", a.get("actorUserId")).update();
            out.put("result", late ? "LATE" : "ATTENDED");
        } else {
            if (att.get("checkin") == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "출근 기록이 없어요. 출근부터 찍어 주세요.");
            if (att.get("checkout") != null) {
                out.put("alreadyRecorded", true);
                return out;
            }
            OffsetDateTime checkin = (OffsetDateTime) att.get("checkin");
            OffsetDateTime we = (OffsetDateTime) a.get("we");
            long minutes = Math.max(0, Duration.between(checkin, OffsetDateTime.now()).toMinutes());
            boolean early = OffsetDateTime.now().isBefore(we.minusMinutes(60));
            jdbc.sql("UPDATE attendances SET checkout_at = now(), checkout_session_id = :s, method_out = 'QR', worked_minutes = :m, result = CASE WHEN :early AND result = 'ATTENDED' THEN 'EARLY_LEAVE' ELSE result END, updated_at = now() WHERE id = :id")
                    .param("s", sessionId).param("m", (int) minutes).param("early", early).param("id", att.get("id")).update();
            out.put("workedMinutes", minutes);
        }
        out.put("alreadyRecorded", false);
        return out;
    }

    private static String sign(String nonce, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(nonce.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ===== 참석 확인 =====

    /** 집합 2시간 전~30분 전에만 가능(기본값은 app_settings). */
    @Transactional
    public Map<String, Object> confirmDeparture(long actorUserId, long applicationId) {
        Map<String, Object> a = jdbc.sql("SELECT status, work_start_at, departure_confirmed_at FROM applications WHERE id = :id AND actor_user_id = :u FOR UPDATE").param("id", applicationId).param("u", actorUserId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", rs.getString(1));
                    m.put("ws", rs.getObject(2, OffsetDateTime.class));
                    m.put("done", rs.getObject(3) != null);
                    return m;
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"CONFIRMED".equals(a.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT);
        if (Boolean.TRUE.equals(a.get("done"))) return Map.of("applicationId", applicationId, "confirmed", true);
        OffsetDateTime ws = (OffsetDateTime) a.get("ws");
        OffsetDateTime now = OffsetDateTime.now();
        if (now.isBefore(ws.minusMinutes(setting("departure_open_before_minutes", 120)))) throw new ResponseStatusException(HttpStatus.CONFLICT, "아직 참석 확인 시간이 아니에요. 집합 2시간 전부터 눌러 주세요.");
        if (now.isAfter(ws.minusMinutes(setting("departure_close_before_minutes", 30)))) throw new ResponseStatusException(HttpStatus.CONFLICT, "참석 확인 시간이 지났어요. 현장 담당자에게 연락해 주세요.");
        jdbc.sql("UPDATE applications SET departure_confirmed_at = now(), updated_at = now() WHERE id = :id").param("id", applicationId).update();
        return Map.of("applicationId", applicationId, "confirmed", true);
    }

    public List<Map<String, Object>> departurePending(long scheduleId) {
        return jdbc.sql("SELECT id AS application_id, applicant_name AS name, applicant_phone AS phone, departure_requested_at FROM applications WHERE schedule_id = :s AND status = 'CONFIRMED' AND departure_confirmed_at IS NULL ORDER BY applicant_name")
                .param("s", scheduleId).query(Rows.camel()).list();
    }

    // ===== 수기 보정 =====

    public record ManualItem(long applicationId, LocalDate workDate, String result, String note) {}

    @Transactional
    public int manual(long userId, long scheduleId, List<ManualItem> items) {
        int n = 0;
        for (ManualItem i : items) {
            if (!List.of("ATTENDED", "LATE", "EARLY_LEAVE", "NO_SHOW", "EXCUSED", "PENDING").contains(i.result())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            Map<String, Object> a = jdbc.sql("SELECT actor_user_id, posting_id FROM applications WHERE id = :id AND schedule_id = :s AND status = 'CONFIRMED'").param("id", i.applicationId()).param("s", scheduleId).query(Rows.camel()).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 일정의 확정 인원이 아니에요."));
            String before = jdbc.sql("SELECT result FROM attendances WHERE application_id = :a AND work_date = :d").param("a", i.applicationId()).param("d", i.workDate()).query(String.class).optional().orElse(null);
            jdbc.sql("""
                    INSERT INTO attendances (application_id, work_date, result, method_in, recorded_by, note) VALUES (:a, :d, :r, 'MANUAL', :u, :n)
                    ON CONFLICT (application_id, work_date) DO UPDATE SET result = EXCLUDED.result, recorded_by = EXCLUDED.recorded_by, note = EXCLUDED.note, updated_at = now()
                    """).param("a", i.applicationId()).param("d", i.workDate()).param("r", i.result()).param("u", userId).param("n", i.note()).update();
            long actor = ((Number) a.get("actorUserId")).longValue();
            if ("NO_SHOW".equals(i.result()) && !"NO_SHOW".equals(before)) {
                int inserted = jdbc.sql("INSERT INTO actor_penalty_events (user_id, posting_id, application_id, kind, note, decided_by) VALUES (:u, :p, :a, 'NO_SHOW', :n, :by) ON CONFLICT DO NOTHING")
                        .param("u", actor).param("p", a.get("postingId")).param("a", i.applicationId()).param("n", i.note()).param("by", userId).update();
                if (inserted > 0) jdbc.sql("UPDATE actor_profiles SET noshow_count = noshow_count + 1, updated_at = now() WHERE user_id = :u").param("u", actor).update();
            } else if ("NO_SHOW".equals(before) && !"NO_SHOW".equals(i.result())) {   // 오기록 정정: 노쇼 무효화
                int voided = jdbc.sql("UPDATE actor_penalty_events SET voided_at = now(), voided_by = :by WHERE application_id = :a AND kind = 'NO_SHOW' AND voided_at IS NULL").param("by", userId).param("a", i.applicationId()).update();
                if (voided > 0) jdbc.sql("UPDATE actor_profiles SET noshow_count = GREATEST(noshow_count - 1, 0), updated_at = now() WHERE user_id = :u").param("u", actor).update();
            }
            n++;
        }
        return n;
    }

    // ===== 시스템 =====

    /** 집합 2시간 전이 된 확정 인원에게 참석 확인을 요청한다. */
    @Transactional
    public int requestDepartureConfirmations() {
        int open = setting("departure_open_before_minutes", 120), close = setting("departure_close_before_minutes", 30);
        List<Map<String, Object>> list = jdbc.sql("""
                SELECT id, actor_user_id FROM applications WHERE status = 'CONFIRMED' AND departure_requested_at IS NULL AND departure_confirmed_at IS NULL
                   AND work_start_at - make_interval(mins => :open) <= now() AND work_start_at - make_interval(mins => :close) > now() FOR UPDATE SKIP LOCKED
                """).param("open", open).param("close", close).query(Rows.camel()).list();
        for (Map<String, Object> a : list) {
            long id = ((Number) a.get("id")).longValue();
            jdbc.sql("UPDATE applications SET departure_requested_at = now() WHERE id = :id").param("id", id).update();
            notifications.notify(((Number) a.get("actorUserId")).longValue(), "DEPARTURE_CONFIRM", "⏰ 참석 확인을 눌러 주세요", "집합 2시간 전이에요. 참석 확인을 눌러 주세요.", "/me/applications/" + id, true, true, Map.of("applicationId", id), null);
        }
        return list.size();
    }

    /** 집합 30분 전까지 응답하지 않은 인원이 있으면 일정당 한 번 반장·담당자에게 알린다. */
    @Transactional
    public int notifyMissingDepartures() {
        int close = setting("departure_close_before_minutes", 30);
        List<Map<String, Object>> list = jdbc.sql("""
                SELECT a.schedule_id, p.company_id, p.id AS posting_id, count(*) AS n FROM applications a JOIN job_postings p ON p.id = a.posting_id
                 WHERE a.status = 'CONFIRMED' AND a.departure_confirmed_at IS NULL AND a.work_start_at - make_interval(mins => :close) <= now() AND a.work_start_at > now()
                 GROUP BY a.schedule_id, p.company_id, p.id
                """).param("close", close).query(Rows.camel()).list();
        int sent = 0;
        for (Map<String, Object> s : list) {
            long scheduleId = ((Number) s.get("scheduleId")).longValue();
            boolean already = jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'DEPARTURE_MISSING' AND (payload->>'scheduleId')::bigint = :s").param("s", scheduleId).query(Integer.class).single() > 0;
            if (already) continue;
            List<Long> users = jdbc.sql("SELECT user_id FROM company_members WHERE company_id = :c AND status = 'ACTIVE' AND member_role IN ('OWNER','MANAGER','SCHEDULER','FIELD_LEADER')").param("c", s.get("companyId")).query(Long.class).list();
            for (Long u : users) notifications.notify(u, "DEPARTURE_MISSING", "참석 확인이 안 된 인원이 있어요", s.get("n") + "명이 아직 응답하지 않았어요.", "/company/jobs/" + s.get("postingId"), false, false, Map.of("scheduleId", scheduleId), null);
            sent++;
        }
        return sent;
    }
}
