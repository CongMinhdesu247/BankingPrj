package com.minh.banking.fraud;

import com.minh.banking.transaction.service.TransferCommitted;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

/** Explicit infrastructure-free regression mode; never an automatic broker-outage fallback. */
@Component @ConditionalOnProperty(name="banking.kafka.enabled",havingValue="false")
public class DirectFraudTrigger {
    private final FraudRecoveryJob recovery;
    public DirectFraudTrigger(FraudRecoveryJob recovery) { this.recovery=recovery; }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void committed(TransferCommitted event) { recovery.attempt(event.transactionId()); }
}
