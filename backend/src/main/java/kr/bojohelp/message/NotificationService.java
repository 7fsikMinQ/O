package kr.bojohelp.message;

import java.util.List;
import java.util.Map;
import kr.bojohelp.common.Json;
import kr.bojohelp.common.Rows;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 인앱 알림(notifications). 푸시(FCM)·폴백 메일은 아직 연결하지 않았고, 앱이 알림함을 불러와 보여 준다. */
@Service
public class NotificationService {

    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long notify(long userId, String type, String title, String body, String linkPath, boolean critical, boolean requiresAck, Map<String, Object> payload, Long broadcastId) {
        return jdbc.sql("""
                INSERT INTO notifications (user_id, broadcast_id, type, title, body, link_path, payload, priority, requires_ack)
                VALUES (:u, :b, :t, :title, :body, :link, CAST(:p AS jsonb), :prio, :ack) RETURNING id
                """)
                .param("u", userId).param("b", broadcastId).param("t", type).param("title", title).param("body", body).param("link", linkPath)
                .param("p", payload == null ? null : Json.write(payload)).param("prio", critical ? "CRITICAL" : "NORMAL").param("ack", requiresAck)
                .query(Long.class).single();
    }

    public long notify(long userId, String type, String title, String body, String linkPath) {
        return notify(userId, type, title, body, linkPath, false, false, null, null);
    }

    /** 업체의 활성 구성원(직책 제한) 모두에게. */
    public void notifyCompany(long companyId, List<String> roles, String type, String title, String body, String linkPath) {
        List<Long> users = jdbc.sql("SELECT user_id FROM company_members WHERE company_id = :c AND status = 'ACTIVE' AND member_role IN (:r)")
                .param("c", companyId).param("r", roles).query(Long.class).list();
        for (Long u : users) notify(u, type, title, body, linkPath);
    }

    public List<Map<String, Object>> list(long userId, Long beforeId, int limit) {
        return jdbc.sql("""
                SELECT id, type, title, body, link_path, priority, requires_ack, acked_at, read_at, created_at FROM notifications
                 WHERE user_id = :u AND (CAST(:before AS bigint) IS NULL OR id < :before) ORDER BY id DESC LIMIT :n
                """)
                .param("u", userId).param("before", beforeId).param("n", Math.min(Math.max(limit, 1), 100)).query(Rows.camel()).list();
    }

    public int unreadCount(long userId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = :u AND read_at IS NULL").param("u", userId).query(Integer.class).single();
    }

    public void markRead(long userId, long id) {
        int n = jdbc.sql("UPDATE notifications SET read_at = COALESCE(read_at, now()) WHERE id = :id AND user_id = :u").param("id", id).param("u", userId).update();
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    public int markAllRead(long userId) {
        return jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = :u AND read_at IS NULL").param("u", userId).update();
    }
}
