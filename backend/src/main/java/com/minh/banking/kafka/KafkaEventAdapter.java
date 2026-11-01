package com.minh.banking.kafka;

import com.minh.banking.fraud.*;
import com.minh.banking.transaction.service.*;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component @ConditionalOnProperty(name="banking.kafka.enabled",havingValue="true",matchIfMissing=true)
public class KafkaEventAdapter {
    private final EventOutbox publisher;
    private final EventCodec codec;
    private final TransactionFraudQueryService transfers;
    private final TransferAttemptService attempts;
    private final FraudEventQueryService alerts;
    public KafkaEventAdapter(EventOutbox publisher,EventCodec codec,TransactionFraudQueryService transfers,TransferAttemptService attempts,FraudEventQueryService alerts) {
        this.publisher=publisher; this.codec=codec; this.transfers=transfers; this.attempts=attempts; this.alerts=alerts;
    }
    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void completed(TransferCommitted event) {
        var fact=transfers.find(event.transactionId()).orElseThrow(InvalidEventException::new);
        publisher.enqueue(codec.create(EventType.TRANSFER_COMPLETED,fact.id(),fact.at(),correlation(fact.id()),
            new TransferCompletedEvent(fact.id(),fact.sourceAccountId())),fact.sourceAccountId().toString());
    }
    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void failed(TransferAttemptService.Failed event) {
        var fact=attempts.find(event.attemptId()).orElseThrow(InvalidEventException::new);
        publisher.enqueue(codec.create(EventType.TRANSFER_FAILED,fact.id(),fact.at(),fact.correlationId(),
            new TransferFailedEvent(fact.id(),fact.actorId(),fact.sourceAccountId(),fact.outcome(),fact.reasonCode())),fact.sourceAccountId().toString());
    }
    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void fraud(FraudAlertCreated event) {
        var fact=alerts.find(event.alertId()).orElseThrow(InvalidEventException::new);
        publisher.enqueue(codec.create(EventType.FRAUD_DETECTED,fact.alertId(),fact.at(),correlation(fact.transactionId()),
            new FraudDetectedEvent(fact.alertId(),fact.transactionId(),fact.sourceAccountId())),fact.sourceAccountId().toString());
    }
    private static String correlation(UUID fallback) { String request=MDC.get("requestId"); return request==null?fallback.toString():request; }
}
