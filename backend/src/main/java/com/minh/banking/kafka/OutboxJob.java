package com.minh.banking.kafka;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="banking.kafka.enabled",havingValue="true",matchIfMissing=true)
public class OutboxJob {
    private static final Logger LOG=LoggerFactory.getLogger(OutboxJob.class);
    private final OutboxRelay relay;
    public OutboxJob(OutboxRelay relay) { this.relay=relay; }
    @Scheduled(fixedDelayString="${banking.kafka.outbox-delay-ms:250}",initialDelayString="${banking.kafka.outbox-initial-delay-ms:1000}")
    public void scheduled() {
        try { for (int i=0;i<20 && relay.relayOne();i++) { /* Each call has its own DB transaction. */ } }
        catch (RuntimeException failure) { LOG.warn("Outbox relay deferred: type={}",failure.getClass().getSimpleName()); }
    }
}
