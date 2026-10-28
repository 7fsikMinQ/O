package kr.bojohelp.operation;

import java.util.List;
import kr.bojohelp.message.NotificationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 새 공고 일반 알림(NEW_POSTING_ALERT): ⭐ 출연자에게 먼저 알린 뒤 우선 시간(기본 1분)이 지나면 같은 지역을 쓰는 출연자에게 알린다.
 * 차단한 업체·알림 거부·이미 ⭐ 알림을 받은 사람은 제외하고 한 번에 최대 1000명.
 */
@Component
public class PostingAlertHandler {

    private final JdbcClient jdbc;
    private final NotificationService notifications;

    public PostingAlertHandler(JdbcClient jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    public int handle(long postingId) {
        var posting = jdbc.sql("SELECT company_id, title, status FROM job_postings WHERE id = :id").param("id", postingId)
                .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)}).optional();
        if (posting.isEmpty() || !"OPEN".equals(posting.get()[2])) return 0;
        long companyId = (Long) posting.get()[0];
        List<Long> actors = jdbc.sql("""
                SELECT ap.user_id FROM actor_profiles ap JOIN users u ON u.id = ap.user_id AND u.status = 'ACTIVE'
                 WHERE ap.is_searchable
                   AND (ap.region_id IS NULL OR ap.region_id IN (SELECT s.region_id FROM job_schedules s WHERE s.posting_id = :p AND s.region_id IS NOT NULL) OR NOT EXISTS (SELECT 1 FROM job_schedules s WHERE s.posting_id = :p AND s.region_id IS NOT NULL))
                   AND NOT EXISTS (SELECT 1 FROM actor_company_blocks b WHERE b.actor_user_id = ap.user_id AND b.company_id = :c)
                   AND NOT EXISTS (SELECT 1 FROM company_actor_blocks b WHERE b.actor_user_id = ap.user_id AND b.company_id = :c)
                   AND NOT EXISTS (SELECT 1 FROM company_starred_actors st WHERE st.company_id = :c AND st.actor_user_id = ap.user_id AND ap.allow_star_alerts)
                 LIMIT 1000
                """).param("p", postingId).param("c", companyId).query(Long.class).list();
        for (Long a : actors) notifications.notify(a, "NEW_POSTING", "새 공고가 올라왔어요", (String) posting.get()[1], "/jobs/" + postingId);
        return actors.size();
    }
}
