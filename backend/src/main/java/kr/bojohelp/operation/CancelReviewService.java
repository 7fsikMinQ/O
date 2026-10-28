package kr.bojohelp.operation;

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
 * 확정 후 출연자 취소의 패널티 판정(CLAUDE.md §2-9): 업체 스케줄러(1~4명)가 투표하고 한 명이라도 PENALTY 면 부과(공고당 1건, DB 유니크가 보장).
 * 전원이 NO_PENALTY 이거나 제한 시간(기본 24시간)이 지나면 미부과로 끝난다. 부과는 횟수만 올리고 점수제가 아니다.
 */
@Service
public class CancelReviewService {

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final AuditService audit;

    public CancelReviewService(JdbcClient jdbc, NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
    }

    public List<Map<String, Object>> pending(long companyId, long userId) {
        return jdbc.sql("""
                SELECT r.application_id, r.status, r.created_at, a.applicant_name AS actor_name, a.cancel_reason, a.work_start_at, p.title AS posting_title,
                       (SELECT vote FROM cancellation_review_votes v WHERE v.application_id = r.application_id AND v.scheduler_user_id = :u) AS my_vote,
                       (SELECT count(*) FROM cancellation_review_votes v WHERE v.application_id = r.application_id) AS votes
                  FROM cancellation_reviews r JOIN applications a ON a.id = r.application_id JOIN job_postings p ON p.id = r.posting_id
                 WHERE r.company_id = :c AND r.status = 'PENDING' ORDER BY r.created_at
                """).param("c", companyId).param("u", userId).query(Rows.camel()).list();
    }

    @Transactional
    public Map<String, Object> vote(long userId, long companyId, long applicationId, String vote) {
        if (!List.of("PENALTY", "NO_PENALTY").contains(vote)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String, Object> r = jdbc.sql("SELECT application_id, status, posting_id, actor_user_id FROM cancellation_reviews WHERE application_id = :a AND company_id = :c FOR UPDATE").param("a", applicationId).param("c", companyId)
                .query(Rows.camel()).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"PENDING".equals(r.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 판정이 끝났어요.");
        jdbc.sql("""
                INSERT INTO cancellation_review_votes (application_id, scheduler_user_id, vote) VALUES (:a, :u, :v)
                ON CONFLICT (application_id, scheduler_user_id) DO UPDATE SET vote = EXCLUDED.vote, voted_at = now()
                """).param("a", applicationId).param("u", userId).param("v", vote).update();
        long actor = ((Number) r.get("actorUserId")).longValue();
        boolean anyPenalty = jdbc.sql("SELECT count(*) FROM cancellation_review_votes WHERE application_id = :a AND vote = 'PENALTY'").param("a", applicationId).query(Integer.class).single() > 0;
        if (anyPenalty) {
            jdbc.sql("UPDATE cancellation_reviews SET status = 'PENALIZED', decided_at = now() WHERE application_id = :a").param("a", applicationId).update();
            int inserted = jdbc.sql("INSERT INTO actor_penalty_events (user_id, posting_id, application_id, kind, note, decided_by) VALUES (:u, :p, :a, 'CANCEL_PENALTY', '확정 후 취소', :by) ON CONFLICT DO NOTHING")
                    .param("u", actor).param("p", r.get("postingId")).param("a", applicationId).param("by", userId).update();
            if (inserted > 0) {
                jdbc.sql("UPDATE actor_profiles SET penalty_count = penalty_count + 1, updated_at = now() WHERE user_id = :u").param("u", actor).update();
                notifications.notify(actor, "PENALTY", "취소 패널티가 기록되었어요", "확정 후 취소로 패널티 1회가 기록되었어요.", "/me/applications/" + applicationId);
            }
            audit.record(userId, "CANCEL_PENALTY", "APPLICATION", applicationId, null);
            return Map.of("status", "PENALIZED");
        }
        int schedulers = jdbc.sql("SELECT count(*) FROM company_members WHERE company_id = :c AND status = 'ACTIVE' AND member_role = 'SCHEDULER'").param("c", companyId).query(Integer.class).single();
        int votes = jdbc.sql("SELECT count(*) FROM cancellation_review_votes WHERE application_id = :a").param("a", applicationId).query(Integer.class).single();
        if (votes >= Math.max(schedulers, 1)) {
            jdbc.sql("UPDATE cancellation_reviews SET status = 'CLEARED', decided_at = now() WHERE application_id = :a").param("a", applicationId).update();
            return Map.of("status", "CLEARED");
        }
        return Map.of("status", "PENDING");
    }

    /** 제한 시간이 지난 판정은 미부과(CLEARED)로 닫는다. */
    @Transactional
    public int clearStale() {
        int hours = Integer.parseInt(jdbc.sql("SELECT value FROM app_settings WHERE key = 'cancel_review_timeout_hours'").query(String.class).optional().orElse("24"));
        return jdbc.sql("UPDATE cancellation_reviews SET status = 'CLEARED', decided_at = now() WHERE status = 'PENDING' AND created_at < now() - make_interval(hours => :h)").param("h", hours).update();
    }
}
