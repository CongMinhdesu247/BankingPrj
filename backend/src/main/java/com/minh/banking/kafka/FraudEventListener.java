package com.minh.banking.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="banking.kafka.enabled", havingValue="true", matchIfMissing=true)
public class FraudEventListener {
    private final EventCodec codec;
    private final KafkaProperties properties;
    private final FraudEventConsumer consumer;
    public FraudEventListener(EventCodec codec, KafkaProperties properties, FraudEventConsumer consumer) {
        this.codec = codec;
        this.properties = properties;
        this.consumer = consumer;
    }
    @KafkaListener(id="${banking.kafka.topic-prefix}.fraud", topics="${banking.kafka.topic-prefix}.transfer.completed.v1",
        groupId="${banking.kafka.topic-prefix}.fraud.v1", containerFactory="fraudKafkaListenerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        var event = codec.decode(record.value(), record.topic(), record.key(), properties);
        try (var ignored=org.slf4j.MDC.putCloseable("requestId", event.correlationId())) {
            consumer.process(event);
        }
    }
}
