package com.company.loopengine.gitlab.webhook;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookDeliveryRepository {
    private final JdbcClient jdbc;

    public WebhookDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(String eventUuid, String eventName, String payload, Instant receivedAt) {
        return jdbc.sql("""
            insert into gitlab_webhook_delivery(event_uuid,event_name,payload_json,received_at)
            values (:uuid,:name,cast(:payload as jsonb),:received)
            on conflict (event_uuid) do nothing
            """)
            .param("uuid", eventUuid).param("name", eventName)
            .param("payload", payload).param("received", Timestamp.from(receivedAt)).update() == 1;
    }

    public long count() {
        return jdbc.sql("select count(*) from gitlab_webhook_delivery")
            .query(Long.class)
            .single();
    }
}
