package com.minh.banking.kafka;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class ProcessedEventRepository {
    private final JdbcTemplate jdbc;
    private final EventCodec codec;
    public ProcessedEventRepository(JdbcTemplate jdbc,EventCodec codec) { this.jdbc=jdbc; this.codec=codec; }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean claim(String consumer,EventEnvelope event) {
        String hash=codec.fingerprint(event);
        int inserted=jdbc.update("""
            INSERT INTO processed_events(consumer_name,event_id,event_type,payload_hash) VALUES (?,?,?,?)
            ON CONFLICT(consumer_name,event_id) DO NOTHING
            """,consumer,event.eventId(),event.eventType().name(),hash);
        if (inserted==1) return true;
        var stored=jdbc.queryForObject("SELECT payload_hash FROM processed_events WHERE consumer_name=? AND event_id=?",String.class,consumer,event.eventId());
        if (stored!=null && !stored.equals(hash)) throw new InvalidEventException();
        return false;
    }
}
