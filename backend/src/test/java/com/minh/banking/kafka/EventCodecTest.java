package com.minh.banking.kafka;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class EventCodecTest {
    private final EventCodec codec=new EventCodec(JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build());
    private final KafkaProperties properties=new KafkaProperties(true,"banking.test.codec",3,50,2);
    private final UUID transaction=UUID.randomUUID(),source=UUID.randomUUID();
    private EventEnvelope event() { return codec.create(EventType.TRANSFER_COMPLETED,transaction,Instant.parse("2026-10-28T05:00:00Z"),"request-1",new TransferCompletedEvent(transaction,source)); }
    @Test void stableEventIdIsIndependentOfPublicationTimeAndSeparatesTypes() {
        assertThat(event().eventId()).isEqualTo(event().eventId());
        assertThat(EventIds.forAggregate(EventType.TRANSFER_FAILED,transaction)).isNotEqualTo(event().eventId());
    }
    @Test void minimalCompletedContractRoundTripsWithoutCredentialsOrMonetaryState() {
        String value=codec.encode(event());
        var decoded=codec.decode(value,EventType.TRANSFER_COMPLETED.topic(properties.topicPrefix()),source.toString(),properties);
        assertThat(decoded).isEqualTo(event());
        assertThat(value).doesNotContain("password","balance","amount","username","token");
    }
    @ParameterizedTest @ValueSource(strings={"", "not-json", "{}", "null", "[]"})
    void malformedInputsHaveSafeErrors(String value) {
        assertThatThrownBy(()->codec.decode(value,EventType.TRANSFER_COMPLETED.topic(properties.topicPrefix()),source.toString(),properties))
            .isInstanceOf(InvalidEventException.class).hasMessage("Event contract or PostgreSQL evidence is invalid");
    }
    @Test void unsupportedVersionWrongTopicWrongKeyAndUnknownFieldsAreRejected() {
        var value=codec.encode(event()); String topic=EventType.TRANSFER_COMPLETED.topic(properties.topicPrefix());
        assertThatThrownBy(()->codec.decode(value.replace("\"schemaVersion\":1","\"schemaVersion\":2"),topic,source.toString(),properties)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(()->codec.decode(value,EventType.TRANSFER_FAILED.topic(properties.topicPrefix()),source.toString(),properties)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(()->codec.decode(value,topic,UUID.randomUUID().toString(),properties)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(()->codec.decode(value.substring(0,value.length()-1)+",\"password\":\"must-not-be-accepted\"}",topic,source.toString(),properties)).isInstanceOf(InvalidEventException.class);
    }
    @Test void whitespaceAndPayloadPropertyOrderCanonicalizeForReceiptHash() {
        var original=event(); String text=codec.encode(original);
        String reordered=text.replace(codec.encode(original).substring(text.indexOf("\"payload\":")+10,text.length()-1),"{\"sourceAccountId\":\""+source+"\",\"transactionId\":\""+transaction+"\"}");
        var canonical=codec.decode(reordered,EventType.TRANSFER_COMPLETED.topic(properties.topicPrefix()),source.toString(),properties);
        assertThat(codec.fingerprint(canonical)).isEqualTo(codec.fingerprint(original));
    }
    @Test void aggregateMismatchAndOversizedRecordsCannotReachDatabaseConsumer() {
        var event=event(); var changed=new EventEnvelope(event.eventId(),event.eventType(),1,event.occurredAt(),event.correlationId(),UUID.randomUUID(),event.payload());
        String topic=EventType.TRANSFER_COMPLETED.topic(properties.topicPrefix());
        assertThatThrownBy(()->codec.decode(codec.encode(changed),topic,source.toString(),properties)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(()->codec.decode("x".repeat(8193),topic,source.toString(),properties)).isInstanceOf(InvalidEventException.class);
    }
}
