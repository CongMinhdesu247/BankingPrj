package com.minh.banking.notification;

import com.minh.banking.fraud.FraudEventQueryService;
import com.minh.banking.kafka.*;
import com.minh.banking.transaction.service.*;
import com.minh.banking.user.service.NotificationRecipientService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationEventConsumer {
    public static final String NAME="notification-v1";
    private final ProcessedEventRepository receipts;
    private final EventCodec codec;
    private final TransactionFraudQueryService transfers;
    private final TransferAttemptService attempts;
    private final FraudEventQueryService alerts;
    private final NotificationRecipientService recipients;
    private final NotificationService notifications;
    public NotificationEventConsumer(ProcessedEventRepository receipts,EventCodec codec,TransactionFraudQueryService transfers,
            TransferAttemptService attempts,FraudEventQueryService alerts,NotificationRecipientService recipients,NotificationService notifications) {
        this.receipts=receipts; this.codec=codec; this.transfers=transfers; this.attempts=attempts;
        this.alerts=alerts; this.recipients=recipients; this.notifications=notifications;
    }
    @Transactional(timeout=15)
    public void process(EventEnvelope event) {
        // Check committed evidence even on replay. Never trust event actor/recipient or score.
        var business=EventIds.forAggregate(event.eventType(),event.aggregateId());
        switch (event.eventType()) {
            case TRANSFER_COMPLETED -> {
                var payload=codec.payload(event,TransferCompletedEvent.class);
                var fact=transfers.find(payload.transactionId()).orElseThrow(InvalidEventException::new);
                if (!fact.sourceAccountId().equals(payload.sourceAccountId()) || !fact.at().equals(event.occurredAt()) || fact.actorId()==null) throw new InvalidEventException();
                if (!receipts.claim(NAME,event)) return;
                notifications.create(fact.actorId(),business,"TRANSFER_COMPLETED",Map.of("transactionId",fact.id()));
            }
            case TRANSFER_FAILED -> {
                var payload=codec.payload(event,TransferFailedEvent.class);
                var fact=attempts.find(payload.attemptId()).orElseThrow(InvalidEventException::new);
                if (fact.transactionId()!=null || !fact.actorId().equals(payload.actorId()) || !fact.sourceAccountId().equals(payload.sourceAccountId())
                        || !fact.outcome().equals(payload.outcome()) || !Objects.equals(fact.reasonCode(),payload.reasonCode()) || !fact.at().equals(event.occurredAt())) throw new InvalidEventException();
                if (!receipts.claim(NAME,event)) return;
                notifications.create(fact.actorId(),business,"TRANSFER_FAILED",Map.of("attemptId",fact.id(),"reasonCode",fact.reasonCode(),"outcome",fact.outcome()));
            }
            case FRAUD_DETECTED -> {
                var payload=codec.payload(event,FraudDetectedEvent.class);
                var fact=alerts.find(payload.alertId()).orElseThrow(InvalidEventException::new);
                if (!fact.transactionId().equals(payload.transactionId()) || !fact.sourceAccountId().equals(payload.sourceAccountId()) || !fact.at().equals(event.occurredAt())) throw new InvalidEventException();
                if (!receipts.claim(NAME,event)) return;
                for (UUID recipient:recipients.riskOfficers()) notifications.create(recipient,business,"FRAUD_DETECTED",
                    Map.of("alertId",fact.alertId(),"transactionId",fact.transactionId(),"riskLevel",fact.riskLevel(),"score",fact.score()));
            }
        }
    }
}
