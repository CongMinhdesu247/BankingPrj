package com.minh.banking.kafka;

import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.LongAdder;

/** Relay acknowledgements are required before marking an outbox row published. */
@Component
public class KafkaDomainEventPublisher implements DomainEventPublisher {
    private static final Logger LOG=LoggerFactory.getLogger(KafkaDomainEventPublisher.class);
    private final ObjectProvider<KafkaTemplate<String,String>> templates;
    private final KafkaProperties properties;
    private final EventCodec codec;
    private final LongAdder sent=new LongAdder(),failed=new LongAdder();
    public KafkaDomainEventPublisher(ObjectProvider<KafkaTemplate<String,String>> templates,KafkaProperties properties,EventCodec codec) {
        this.templates=templates; this.properties=properties; this.codec=codec;
    }
    public void publish(EventEnvelope event,String sourceAccountKey) {
        publishConfirmed(event,sourceAccountKey);
    }
    public void publishConfirmed(EventEnvelope event,String sourceAccountKey) {
        if (!properties.enabled()) throw new IllegalStateException("Kafka is disabled");
        try {
            templates.getObject().send(event.eventType().topic(properties.topicPrefix()),sourceAccountKey,codec.encode(event))
                .get(5,java.util.concurrent.TimeUnit.SECONDS);
            sent.increment();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); lost(event,failure);
            throw new IllegalStateException("Kafka publication interrupted",failure);
        } catch (Exception failure) {
            lost(event,failure);
            throw new IllegalStateException("Kafka publication unavailable",failure);
        }
    }
    private void lost(EventEnvelope event,Throwable failure) {
        failed.increment();
        LOG.warn("Kafka event publish deferred for retry: type={}, eventId={}, failureType={}",event.eventType(),event.eventId(),failure.getClass().getSimpleName());
    }
    public record Stats(long sent,long failed) {}
    public Stats stats() { return new Stats(sent.sum(),failed.sum()); }
}
