package kr.bojohelp.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 아웃박스를 주기적으로 처리한다. 시험에서는 app.outbox.enabled=false 로 꺼 두고 OutboxWorker.runOnce() 를 직접 호출한다. */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);
    private final OutboxWorker worker;

    OutboxScheduler(OutboxWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-millis:2000}")
    void poll() {
        try {
            worker.runOnce();
        } catch (RuntimeException e) {
            log.warn("아웃박스 처리 중 오류: {}", e.getClass().getSimpleName());   // 수신자·본문은 남기지 않는다
        }
    }
}
