package kr.bojohelp.outbox;

import java.util.List;
import kr.bojohelp.mail.EmailSender;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아웃박스 워커: 도메인 트랜잭션이 남긴 이벤트를 `FOR UPDATE SKIP LOCKED` 로 가져와 처리한다(여러 서버가 떠도 한 번만).
 * 지금은 EMAIL_SEND 만 처리한다. 성공하면 본문(토큰·임시 비밀번호)을 지우고 DONE, 실패하면 시도 횟수를 올리고 늦춰서 재시도하며 5회 실패 시 FAILED(본문 삭제).
 * 로그에는 수신자·본문을 남기지 않는다.
 */
@Component
public class OutboxWorker {

    private static final int MAX_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final EmailSender email;
    private final TransactionTemplate tx;

    public OutboxWorker(JdbcClient jdbc, EmailSender email, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.email = email;
        this.tx = tx;
    }

    /** 한 번 처리하고 처리한 건수를 돌려준다(시험에서 직접 호출). */
    public int runOnce() {
        Integer n = tx.execute(status -> {
            List<Object[]> rows = jdbc.sql("""
                    SELECT id, payload->>'to', payload->>'subject', payload->>'body', attempts FROM outbox_events
                     WHERE status = 'NEW' AND event_type = 'EMAIL_SEND' AND available_at <= now()
                     ORDER BY id LIMIT 20 FOR UPDATE SKIP LOCKED
                    """)
                    .query((rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)}).list();
            for (Object[] r : rows) {
                long id = (Long) r[0];
                int attempts = (Integer) r[4];
                try {
                    email.send((String) r[1], (String) r[2], (String) r[3]);
                    jdbc.sql("UPDATE outbox_events SET status = 'DONE', processed_at = now(), payload = '{\"sent\":true}'::jsonb, last_error = NULL WHERE id = :id").param("id", id).update();
                } catch (RuntimeException e) {
                    int next = attempts + 1;
                    String summary = e.getClass().getSimpleName();
                    if (next >= MAX_ATTEMPTS) {
                        jdbc.sql("UPDATE outbox_events SET status = 'FAILED', attempts = :a, last_error = :e, payload = '{}'::jsonb WHERE id = :id").param("a", next).param("e", summary).param("id", id).update();
                    } else {
                        jdbc.sql("UPDATE outbox_events SET attempts = :a, last_error = :e, available_at = now() + make_interval(mins => :m) WHERE id = :id")
                                .param("a", next).param("e", summary).param("m", next * next).param("id", id).update();
                    }
                }
            }
            return rows.size();
        });
        return n == null ? 0 : n;
    }
}
