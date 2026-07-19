package com.company.loopengine.shared.outbox;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    private final JdbcClient jdbc;
    public OutboxRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void append(OutboxEvent event) {
        jdbc.sql("""
            insert into outbox_event(id, aggregate_type, aggregate_id, event_type, payload_json, occurred_at)
            values (:id, :aggregateType, :aggregateId, :eventType, cast(:payload as jsonb), :occurredAt)
            on conflict (id) do nothing
            """)
            .param("id", event.id()).param("aggregateType", event.aggregateType())
            .param("aggregateId", event.aggregateId()).param("eventType", event.eventType())
            .param("payload", event.payloadJson())
            .param("occurredAt", Timestamp.from(event.occurredAt()))
            .update();
    }

    public List<OutboxEvent> findPending(int limit) {
        return jdbc.sql("""
            select id, aggregate_type, aggregate_id, event_type, payload_json::text, occurred_at
            from outbox_event where processed_at is null and available_at <= now()
            order by occurred_at limit :limit
            """).param("limit", limit).query(OutboxEvent.class).list();
    }
}
