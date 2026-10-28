package kr.bojohelp.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 감사 로그(audit_logs)와 아웃박스(outbox_events) 기록 — 도메인 트랜잭션 안에서 호출해 원자적으로 남긴다. after 에는 개인정보·비밀을 넣지 않는다. */
@Service
public class AuditService {

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Long actorUserId, String action, String entityType, Long entityId, String afterJson) {
        jdbc.sql("INSERT INTO audit_logs (actor_user_id, action, entity_type, entity_id, after_data) VALUES (:u, :a, :t, :id, CAST(:d AS jsonb))")
                .param("u", actorUserId).param("a", action).param("t", entityType).param("id", entityId).param("d", afterJson == null ? "{}" : afterJson).update();
    }

    public void publish(String aggregateType, long aggregateId, String eventType, String payloadJson) {
        jdbc.sql("INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload) VALUES (:t, :id, :e, CAST(:p AS jsonb))")
                .param("t", aggregateType).param("id", aggregateId).param("e", eventType).param("p", payloadJson).update();
    }
}
