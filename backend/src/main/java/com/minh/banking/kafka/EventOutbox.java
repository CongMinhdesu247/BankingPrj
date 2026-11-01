package com.minh.banking.kafka;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Enqueue in the business transaction; a rollback must also remove the event. */
@Service
public class EventOutbox {
    private final JdbcTemplate jdbc;
    private final EventCodec codec;
    private final Clock clock;
    public EventOutbox(JdbcTemplate jdbc,EventCodec codec,Clock clock) { this.jdbc=jdbc; this.codec=codec; this.clock=clock; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(EventEnvelope event,String sourceAccountKey) {
        jdbc.update("""
            INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,schema_version,correlation_id,occurred_at,payload,next_attempt_at)
            VALUES (?,'ACCOUNT',?,?,1,?,?,cast(? as jsonb),?) ON CONFLICT(id) DO NOTHING
            """,event.eventId(),java.util.UUID.fromString(sourceAccountKey),event.eventType().name(),event.correlationId(),
            Timestamp.from(event.occurredAt()),codec.encode(event),Timestamp.from(clock.instant()));
    }
}
