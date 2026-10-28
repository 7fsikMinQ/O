package kr.bojohelp.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.bojohelp.audit.AuditService;
import kr.bojohelp.common.Rows;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 업체 ↔ 출연자 메시지. 시작은 업체만, 출연자는 답장만(출연자끼리의 소통은 없다). 업체는 자기 업체에 지원한 적이 있는 출연자에게만 보낼 수 있고,
 * 출연자가 차단한 업체·업체가 차단한 출연자에게는 보낼 수 없다. 단체 공지(broadcast)는 수신자마다 대화방과 읽음 확인을 만든다.
 */
@Service
public class MessageService {

    public record StartReq(@NotNull Long actorUserId, Long postingId, @Size(max = 200) String subject, @NotBlank @Size(max = 2000) String body) {}

    public record BroadcastReq(@NotNull Long postingId, Long scheduleId, @NotNull String target, List<Long> applicationIds,
            @NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 2000) String body) {}

    private static final int MAX_REMINDERS = 5;

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final AuditService audit;

    public MessageService(JdbcClient jdbc, NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ===== 업체: 개별 메시지 =====

    @Transactional
    public Map<String, Object> start(long userId, long companyId, StartReq r) {
        guardRecipient(companyId, r.actorUserId());
        long thread = jdbc.sql("INSERT INTO message_threads (company_id, actor_user_id, posting_id, started_by_user_id, subject) VALUES (:c, :a, :p, :u, :s) RETURNING id")
                .param("c", companyId).param("a", r.actorUserId()).param("p", r.postingId()).param("u", userId).param("s", r.subject()).query(Long.class).single();
        addMessage(thread, userId, "COMPANY", r.body());
        notifyActor(r.actorUserId(), companyId, r.subject(), r.body(), thread, null);
        return Map.of("threadId", thread);
    }

    @Transactional
    public Map<String, Object> companyReply(long userId, long companyId, long threadId, String body) {
        Map<String, Object> t = companyThread(companyId, threadId);
        guardRecipient(companyId, ((Number) t.get("actorUserId")).longValue());
        addMessage(threadId, userId, "COMPANY", body);
        notifyActor(((Number) t.get("actorUserId")).longValue(), companyId, (String) t.get("subject"), body, threadId, null);
        return Map.of("threadId", threadId);
    }

    /** 이 업체에 지원한 적이 있고, 서로 차단하지 않은 출연자만. */
    private void guardRecipient(long companyId, long actorUserId) {
        int applied = jdbc.sql("SELECT count(*) FROM applications a JOIN job_postings p ON p.id = a.posting_id WHERE p.company_id = :c AND a.actor_user_id = :a").param("c", companyId).param("a", actorUserId).query(Integer.class).single();
        if (applied == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 업체에 지원한 출연자에게만 메시지를 보낼 수 있어요.");
        if (jdbc.sql("SELECT count(*) FROM actor_company_blocks WHERE actor_user_id = :a AND company_id = :c").param("a", actorUserId).param("c", companyId).query(Integer.class).single() > 0
                || jdbc.sql("SELECT count(*) FROM company_actor_blocks WHERE actor_user_id = :a AND company_id = :c").param("a", actorUserId).param("c", companyId).query(Integer.class).single() > 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "메시지를 보낼 수 없어요.");
        }
    }

    private void addMessage(long threadId, long senderId, String side, String body) {
        jdbc.sql("INSERT INTO messages (thread_id, sender_user_id, sender_side, body) VALUES (:t, :u, :s, :b)").param("t", threadId).param("u", senderId).param("s", side).param("b", body).update();
        jdbc.sql("UPDATE message_threads SET last_message_at = now() WHERE id = :t").param("t", threadId).update();
    }

    private void notifyActor(long actorUserId, long companyId, String subject, String body, long threadId, Long broadcastId) {
        String company = jdbc.sql("SELECT name FROM companies WHERE id = :c").param("c", companyId).query(String.class).single();
        notifications.notify(actorUserId, broadcastId == null ? "MESSAGE" : "BROADCAST", company + (subject == null ? "" : " · " + subject),
                body.length() > 100 ? body.substring(0, 100) + "…" : body, "/me/messages/threads/" + threadId, broadcastId != null, false, Map.of("threadId", threadId), broadcastId);
    }

    public List<Map<String, Object>> companyThreads(long companyId) {
        return jdbc.sql("""
                SELECT t.id, t.subject, t.last_message_at, t.actor_user_id, u.name AS actor_name, t.posting_id, t.broadcast_id,
                       (SELECT body FROM messages m WHERE m.thread_id = t.id ORDER BY m.id DESC LIMIT 1) AS last_body,
                       (SELECT count(*) FROM messages m WHERE m.thread_id = t.id AND m.sender_side = 'ACTOR' AND m.read_at IS NULL) AS unread
                  FROM message_threads t JOIN users u ON u.id = t.actor_user_id WHERE t.company_id = :c ORDER BY t.last_message_at DESC LIMIT 200
                """).param("c", companyId).query(Rows.camel()).list();
    }

    @Transactional
    public Map<String, Object> companyThread(long companyId, long threadId, boolean markRead) {
        Map<String, Object> t = companyThread(companyId, threadId);
        t.put("messages", jdbc.sql("SELECT id, sender_side, body, read_at, created_at FROM messages WHERE thread_id = :t ORDER BY id").param("t", threadId).query(Rows.camel()).list());
        if (markRead) jdbc.sql("UPDATE messages SET read_at = now() WHERE thread_id = :t AND sender_side = 'ACTOR' AND read_at IS NULL").param("t", threadId).update();
        return t;
    }

    private Map<String, Object> companyThread(long companyId, long threadId) {
        return jdbc.sql("SELECT t.id, t.subject, t.actor_user_id, u.name AS actor_name, t.posting_id, t.broadcast_id FROM message_threads t JOIN users u ON u.id = t.actor_user_id WHERE t.id = :t AND t.company_id = :c")
                .param("t", threadId).param("c", companyId).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // ===== 출연자: 받은 메시지·답장 =====

    public List<Map<String, Object>> actorThreads(long actorUserId) {
        return jdbc.sql("""
                SELECT t.id, t.subject, t.last_message_at, c.name AS company_name, t.broadcast_id,
                       (SELECT body FROM messages m WHERE m.thread_id = t.id ORDER BY m.id DESC LIMIT 1) AS last_body,
                       (SELECT count(*) FROM messages m WHERE m.thread_id = t.id AND m.sender_side = 'COMPANY' AND m.read_at IS NULL) AS unread
                  FROM message_threads t JOIN companies c ON c.id = t.company_id WHERE t.actor_user_id = :a ORDER BY t.last_message_at DESC LIMIT 200
                """).param("a", actorUserId).query(Rows.camel()).list();
    }

    /** 대화를 열면 업체가 보낸 메시지는 읽음 처리되고, 공지라면 읽음 확인(수신 현황)에 기록된다. */
    @Transactional
    public Map<String, Object> actorThread(long actorUserId, long threadId) {
        Map<String, Object> t = jdbc.sql("SELECT t.id, t.subject, t.broadcast_id, t.company_id, c.name AS company_name FROM message_threads t JOIN companies c ON c.id = t.company_id WHERE t.id = :t AND t.actor_user_id = :a")
                .param("t", threadId).param("a", actorUserId).query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        t.put("messages", jdbc.sql("SELECT id, sender_side, body, read_at, created_at FROM messages WHERE thread_id = :t ORDER BY id").param("t", threadId).query(Rows.camel()).list());
        jdbc.sql("UPDATE messages SET read_at = now() WHERE thread_id = :t AND sender_side = 'COMPANY' AND read_at IS NULL").param("t", threadId).update();
        jdbc.sql("UPDATE job_broadcast_recipients SET read_at = COALESCE(read_at, now()) WHERE thread_id = :t AND actor_user_id = :a").param("t", threadId).param("a", actorUserId).update();
        jdbc.sql("UPDATE notifications SET read_at = COALESCE(read_at, now()) WHERE user_id = :a AND link_path = :l").param("a", actorUserId).param("l", "/me/messages/threads/" + threadId).update();
        t.put("blocked", jdbc.sql("SELECT count(*) FROM actor_company_blocks WHERE actor_user_id = :a AND company_id = :c").param("a", actorUserId).param("c", t.get("companyId")).query(Integer.class).single() > 0);
        return t;
    }

    @Transactional
    public Map<String, Object> actorReply(long actorUserId, long threadId, String body) {
        Map<String, Object> t = jdbc.sql("SELECT company_id, subject FROM message_threads WHERE id = :t AND actor_user_id = :a").param("t", threadId).param("a", actorUserId)
                .query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        long companyId = ((Number) t.get("companyId")).longValue();
        if (jdbc.sql("SELECT count(*) FROM actor_company_blocks WHERE actor_user_id = :a AND company_id = :c").param("a", actorUserId).param("c", companyId).query(Integer.class).single() > 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "차단한 업체에는 답장할 수 없어요.");
        }
        addMessage(threadId, actorUserId, "ACTOR", body);
        notifications.notifyCompany(companyId, List.of("OWNER", "MANAGER", "SCHEDULER", "FIELD_LEADER"), "MESSAGE_REPLY", "출연자가 답장했어요", body.length() > 80 ? body.substring(0, 80) + "…" : body, "/company/messages");
        return Map.of("threadId", threadId);
    }

    @Transactional
    public void blockCompany(long actorUserId, long companyId, boolean block) {
        if (block) jdbc.sql("INSERT INTO actor_company_blocks (actor_user_id, company_id) VALUES (:a, :c) ON CONFLICT DO NOTHING").param("a", actorUserId).param("c", companyId).update();
        else jdbc.sql("DELETE FROM actor_company_blocks WHERE actor_user_id = :a AND company_id = :c").param("a", actorUserId).param("c", companyId).update();
    }

    // ===== 업체: 단체 공지(읽음 확인) =====

    /**
     * 단체 알림/메시지. target: ALL_SELECTED / ALL_CONFIRMED / DEPARTURE_UNCONFIRMED / CLOSED_AVAILABLE / CUSTOM(applicationIds).
     * 수신자마다 대화방을 만들고 읽음 여부를 기록해 "안 읽은 사람"에게 다시 알릴 수 있다(최대 5회).
     */
    @Transactional
    public Map<String, Object> broadcast(long userId, long companyId, BroadcastReq r) {
        Integer owner = jdbc.sql("SELECT count(*) FROM job_postings WHERE id = :p AND company_id = :c").param("p", r.postingId()).param("c", companyId).query(Integer.class).single();
        if (owner == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String filter = switch (r.target()) {
            case "ALL_SELECTED" -> "a.status = 'SELECTED'";
            case "ALL_CONFIRMED" -> "a.status = 'CONFIRMED'";
            case "DEPARTURE_UNCONFIRMED" -> "a.status = 'CONFIRMED' AND a.departure_confirmed_at IS NULL";
            case "CLOSED_AVAILABLE" -> "a.status IN ('AUTO_REJECTED','APPLIED')";
            case "CUSTOM" -> "a.id IN (:ids)";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        if ("CUSTOM".equals(r.target()) && (r.applicationIds() == null || r.applicationIds().isEmpty())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "받는 사람을 골라 주세요.");
        var q = jdbc.sql("SELECT a.id, a.actor_user_id FROM applications a WHERE a.posting_id = :p AND (CAST(:sch AS bigint) IS NULL OR a.schedule_id = :sch) AND " + filter
                + " AND NOT EXISTS (SELECT 1 FROM actor_company_blocks b WHERE b.actor_user_id = a.actor_user_id AND b.company_id = :c)").param("p", r.postingId()).param("sch", r.scheduleId()).param("c", companyId);
        if ("CUSTOM".equals(r.target())) q = q.param("ids", r.applicationIds());
        List<Map<String, Object>> recipients = q.query(Rows.camel()).list();
        if (recipients.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "받는 사람이 없어요.");
        long bid = jdbc.sql("INSERT INTO job_broadcasts (company_id, posting_id, schedule_id, sender_user_id, target_filter, title, message_body, recipient_count) VALUES (:c, :p, :s, :u, :t, :title, :body, :n) RETURNING id")
                .param("c", companyId).param("p", r.postingId()).param("s", r.scheduleId()).param("u", userId).param("t", r.target()).param("title", r.title()).param("body", r.body()).param("n", recipients.size()).query(Long.class).single();
        for (Map<String, Object> rec : recipients) {
            long actor = ((Number) rec.get("actorUserId")).longValue();
            long app = ((Number) rec.get("id")).longValue();
            long thread = jdbc.sql("INSERT INTO message_threads (company_id, actor_user_id, posting_id, broadcast_id, started_by_user_id, subject) VALUES (:c, :a, :p, :b, :u, :s) RETURNING id")
                    .param("c", companyId).param("a", actor).param("p", r.postingId()).param("b", bid).param("u", userId).param("s", r.title()).query(Long.class).single();
            addMessage(thread, userId, "COMPANY", r.body());
            jdbc.sql("INSERT INTO job_broadcast_recipients (broadcast_id, application_id, actor_user_id, thread_id, delivered_at) VALUES (:b, :a, :u, :t, now())").param("b", bid).param("a", app).param("u", actor).param("t", thread).update();
            notifyActor(actor, companyId, r.title(), r.body(), thread, bid);
        }
        audit.record(userId, "BROADCAST_SEND", "JOB_BROADCAST", bid, "{\"recipients\":" + recipients.size() + "}");
        return Map.of("broadcastId", bid, "recipientCount", recipients.size());
    }

    public List<Map<String, Object>> broadcasts(long companyId) {
        return jdbc.sql("""
                SELECT b.id, b.title, b.target_filter, b.recipient_count, b.created_at, b.posting_id,
                       (SELECT count(*) FROM job_broadcast_recipients r WHERE r.broadcast_id = b.id AND r.read_at IS NOT NULL) AS read_count
                  FROM job_broadcasts b WHERE b.company_id = :c ORDER BY b.id DESC LIMIT 100
                """).param("c", companyId).query(Rows.camel()).list();
    }

    public Map<String, Object> receipts(long companyId, long broadcastId) {
        Map<String, Object> b = jdbc.sql("SELECT id, title, message_body, recipient_count, created_at FROM job_broadcasts WHERE id = :id AND company_id = :c").param("id", broadcastId).param("c", companyId)
                .query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        b.put("recipients", jdbc.sql("""
                SELECT r.application_id, u.name AS actor_name, r.delivered_at, r.read_at, r.reminder_count, r.reminded_at
                  FROM job_broadcast_recipients r JOIN users u ON u.id = r.actor_user_id WHERE r.broadcast_id = :b ORDER BY r.read_at NULLS FIRST, u.name
                """).param("b", broadcastId).query(Rows.camel()).list());
        return b;
    }

    /** 아직 읽지 않은 사람에게만 다시 알린다(사람당 최대 5회). 문자·카카오가 아니라 인앱 알림이다. */
    @Transactional
    public Map<String, Object> remindUnread(long userId, long companyId, long broadcastId) {
        Map<String, Object> b = jdbc.sql("SELECT id, title, message_body FROM job_broadcasts WHERE id = :id AND company_id = :c").param("id", broadcastId).param("c", companyId)
                .query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<Map<String, Object>> targets = jdbc.sql("SELECT application_id, actor_user_id, thread_id FROM job_broadcast_recipients WHERE broadcast_id = :b AND read_at IS NULL AND reminder_count < :max FOR UPDATE")
                .param("b", broadcastId).param("max", MAX_REMINDERS).query(Rows.camel()).list();
        for (Map<String, Object> t : targets) {
            notifications.notify(((Number) t.get("actorUserId")).longValue(), "BROADCAST_REMIND", "📢 아직 읽지 않은 안내가 있어요", (String) b.get("title"), "/me/messages/threads/" + t.get("threadId"), true, false, null, broadcastId);
            jdbc.sql("UPDATE job_broadcast_recipients SET reminder_count = reminder_count + 1, reminded_at = now() WHERE broadcast_id = :b AND application_id = :a").param("b", broadcastId).param("a", t.get("applicationId")).update();
        }
        return Map.of("reminded", targets.size());
    }

    // ===== ⭐ 출연자 =====

    public List<Map<String, Object>> stars(long companyId) {
        return jdbc.sql("""
                SELECT st.actor_user_id, u.name, st.note, st.created_at, ap.confirmed_count, ap.noshow_count
                  FROM company_starred_actors st JOIN users u ON u.id = st.actor_user_id JOIN actor_profiles ap ON ap.user_id = st.actor_user_id
                 WHERE st.company_id = :c ORDER BY st.created_at DESC
                """).param("c", companyId).query(Rows.camel()).list();
    }

    @Transactional
    public Map<String, Object> star(long userId, long companyId, long actorUserId, String note) {
        int active = jdbc.sql("SELECT count(*) FROM company_starred_actors WHERE company_id = :c AND actor_user_id <> :a").param("c", companyId).param("a", actorUserId).query(Integer.class).single();
        int limit = Integer.parseInt(jdbc.sql("SELECT value FROM app_settings WHERE key = 'star_limit_free'").query(String.class).optional().orElse("30"));
        if (active >= limit) throw new ResponseStatusException(HttpStatus.CONFLICT, "⭐ 출연자는 무료로 " + limit + "명까지 저장할 수 있어요.");
        int worked = jdbc.sql("SELECT count(*) FROM applications a JOIN job_postings p ON p.id = a.posting_id WHERE p.company_id = :c AND a.actor_user_id = :a AND a.status = 'CONFIRMED'").param("c", companyId).param("a", actorUserId).query(Integer.class).single();
        if (worked == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "함께한 적이 있는 출연자만 ⭐ 로 저장할 수 있어요.");
        jdbc.sql("""
                INSERT INTO company_starred_actors (company_id, actor_user_id, note, created_by) VALUES (:c, :a, :n, :u)
                ON CONFLICT (company_id, actor_user_id) DO UPDATE SET note = EXCLUDED.note
                """).param("c", companyId).param("a", actorUserId).param("n", note).param("u", userId).update();
        return Map.of("actorUserId", actorUserId, "starred", true);
    }

    @Transactional
    public void unstar(long companyId, long actorUserId) {
        jdbc.sql("DELETE FROM company_starred_actors WHERE company_id = :c AND actor_user_id = :a").param("c", companyId).param("a", actorUserId).update();
    }

    public void setStarAlerts(long actorUserId, boolean enabled) {
        jdbc.sql("UPDATE actor_profiles SET allow_star_alerts = :e, updated_at = now() WHERE user_id = :u").param("e", enabled).param("u", actorUserId).update();
    }

    // ===== 신고 =====

    public Map<String, Object> report(long userId, String targetType, long targetId, String reasonCode, String detail) {
        long id = jdbc.sql("INSERT INTO reports (reporter_id, target_type, target_id, reason_code, detail) VALUES (:u, :t, :i, :r, :d) RETURNING id")
                .param("u", userId).param("t", targetType).param("i", targetId).param("r", reasonCode).param("d", detail).query(Long.class).single();
        return Map.of("id", id);
    }
}
