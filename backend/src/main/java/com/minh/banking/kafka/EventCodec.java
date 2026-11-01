package com.minh.banking.kafka;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventCodec {
    private final JsonMapper json;
    public EventCodec(JsonMapper json) { this.json=json; }
    public EventEnvelope create(EventType type,UUID aggregate,Instant at,String correlation,Object payload) {
        return new EventEnvelope(EventIds.forAggregate(type,aggregate),type,1,at,correlation,aggregate,json.valueToTree(payload));
    }
    public EventEnvelope decode(String value,String topic,String key,KafkaProperties properties) {
        try {
            if (value==null || value.length()>8192) throw new InvalidEventException();
            var event=json.readValue(value,EventEnvelope.class);
            if (event.eventId()==null || event.eventType()==null || event.schemaVersion()!=1 || event.occurredAt()==null || event.aggregateId()==null
                    || event.correlationId()==null || !event.correlationId().matches("[A-Za-z0-9._:-]{1,100}")
                    || !event.eventType().topic(properties.topicPrefix()).equals(topic) || event.payload()==null || !event.payload().isObject()) throw new InvalidEventException();
            Object typed; UUID aggregate,source;
            switch (event.eventType()) {
                case TRANSFER_COMPLETED -> {
                    var payload=payload(event,TransferCompletedEvent.class); typed=payload;
                    aggregate=payload.transactionId(); source=payload.sourceAccountId();
                }
                case TRANSFER_FAILED -> {
                    var payload=payload(event,TransferFailedEvent.class); typed=payload;
                    aggregate=payload.attemptId(); source=payload.sourceAccountId();
                    if (payload.actorId()==null || !Set.of("BUSINESS_FAILURE","SYSTEM_FAILURE").contains(payload.outcome())
                            || payload.reasonCode()==null || !payload.reasonCode().matches("[A-Z_]{1,100}")) throw new InvalidEventException();
                }
                case FRAUD_DETECTED -> {
                    var payload=payload(event,FraudDetectedEvent.class); typed=payload;
                    aggregate=payload.alertId(); source=payload.sourceAccountId();
                    if (payload.transactionId()==null) throw new InvalidEventException();
                }
                default -> throw new InvalidEventException();
            }
            if (aggregate==null || source==null || !aggregate.equals(event.aggregateId()) || !source.toString().equals(key)) throw new InvalidEventException();
            return new EventEnvelope(event.eventId(),event.eventType(),1,event.occurredAt(),event.correlationId(),aggregate,json.valueToTree(typed));
        } catch (RuntimeException failure) { throw new InvalidEventException(); }
    }
    public <T> T payload(EventEnvelope event,Class<T> type) { return json.treeToValue(event.payload(),type); }
    public String encode(EventEnvelope event) { return json.writeValueAsString(event); }
    public String fingerprint(EventEnvelope event) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(event).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
