package kr.bojohelp.operation;

import java.util.List;
import kr.bojohelp.application.SelectionService;
import kr.bojohelp.posting.PostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1분마다 도는 배치: 지원 마감 시각이 지난 공고 닫기, 응답 기한 지난 합격 통보 만료, 일정 충돌 재알림, 참석 확인 요청·미응답 알림, 오래된 취소 판정 정리, 끝난 공고 완료 처리.
 * 각 작업은 여러 번 실행해도 안전하게(멱등) 만들어져 있고 행 잠금(SKIP LOCKED)으로 서버가 둘이어도 한 번만 처리된다.
 * 시험에서는 runOnce() 를 직접 호출한다(app.outbox.enabled=false 면 예약 실행만 멈춘다).
 */
@Component
public class JobsScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobsScheduler.class);

    private final JdbcClient jdbc;
    private final PostingService postings;
    private final SelectionService selection;
    private final AttendanceService attendance;
    private final CancelReviewService cancelReviews;
    private final boolean enabled;

    public JobsScheduler(JdbcClient jdbc, PostingService postings, SelectionService selection, AttendanceService attendance, CancelReviewService cancelReviews,
            @Value("${app.outbox.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.jdbc = jdbc;
        this.postings = postings;
        this.selection = selection;
        this.attendance = attendance;
        this.cancelReviews = cancelReviews;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        if (!enabled) return;
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("배치 실행 중 오류: {}", e.getClass().getSimpleName());   // 메시지에 개인정보가 섞이지 않게 종류만 남긴다
        }
    }

    public void runOnce() {
        List<Long> due = jdbc.sql("SELECT id FROM job_postings WHERE status = 'OPEN' AND apply_deadline_at IS NOT NULL AND apply_deadline_at < now() AND deleted_at IS NULL").query(Long.class).list();
        for (Long id : due) postings.closeBySystem(id);
        selection.expireOverdue();
        selection.remindOpenConflicts();
        attendance.requestDepartureConfirmations();
        attendance.notifyMissingDepartures();
        cancelReviews.clearStale();
        jdbc.sql("UPDATE job_postings SET status = 'COMPLETED', updated_at = now() WHERE status IN ('OPEN','CLOSED') AND last_shoot_date < ((now() AT TIME ZONE 'Asia/Seoul')::date - 1) AND deleted_at IS NULL").update();
    }
}
