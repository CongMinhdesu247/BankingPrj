package com.minh.banking.kafka;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxRelay {
    private final JdbcTemplate jdbc;
    private final EventCodec codec;
    private final KafkaDomainEventPublisher publisher;
    private final KafkaProperties properties;
    private final Clock clock;
    public OutboxRelay(JdbcTemplate jdbc,EventCodec codec,KafkaDomainEventPublisher publisher,KafkaProperties properties,Clock clock) {
        this.jdbc=jdbc; this.codec=codec; this.publisher=publisher; this.properties=properties; this.clock=clock;
    }
    private record Pending(UUID id,String source,String type,String body,int attempts) {}
    /** One bounded send per transaction. Concurrent workers skip locked rows.
     * Crash after broker ACK but before DB commit redelivers the identical event;
     * consumers' receipts and unique business keys make that safe.
     */
    @Transactional(timeout=15)
    public boolean relayOne() {
        var pending=jdbc.query("""
            SELECT id,aggregate_id,event_type,payload::text,attempts FROM outbox_events
            WHERE status IN ('PENDING','FAILED') AND attempts<20 AND next_attempt_at<=?
            ORDER BY attempts,next_attempt_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """,(rs,n)->new Pending(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5)),Timestamp.from(clock.instant()));
        if (pending.isEmpty()) return false;
        var row=pending.getFirst();
        try {
            var event=codec.decode(row.body(),EventType.valueOf(row.type()).topic(properties.topicPrefix()),row.source(),properties);
            publisher.publishConfirmed(event,row.source());
        } catch (RuntimeException failure) {
            long delay=Math.min(300L,5L*(1L<<Math.min(row.attempts(),6)));
            // No payloads, broker credentials or exception messages in persisted errors.
            jdbc.update("""
                UPDATE outbox_events SET status='FAILED',attempts=attempts+1,next_attempt_at=?,last_error=? WHERE id=?
                """,Timestamp.from(clock.instant().plusSeconds(delay)),failure.getClass().getSimpleName(),row.id());
            return true;
        }
        jdbc.update("""
            UPDATE outbox_events SET status='PUBLISHED',attempts=attempts+1,published_at=?,last_error=NULL WHERE id=?
            """,Timestamp.from(clock.instant()),row.id());
        return true;
    }
}
