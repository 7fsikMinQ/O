package kr.bojohelp.mail;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 메일은 직접 보내지 않고 아웃박스에 넣는다(도메인 트랜잭션과 원자적, 실패 시 재시도). 워커가 보내고 나면 본문(토큰·임시 비밀번호 포함)은 지워진다. */
@Component
public class EmailQueue {

    private final JdbcClient jdbc;

    public EmailQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(long userId, String to, String subject, String body) {
        jdbc.sql("INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload) VALUES ('EMAIL', :u, 'EMAIL_SEND', jsonb_build_object('to', CAST(:to AS text), 'subject', CAST(:s AS text), 'body', CAST(:b AS text)))")
                .param("u", userId).param("to", to).param("s", subject).param("b", body).update();
    }
}
