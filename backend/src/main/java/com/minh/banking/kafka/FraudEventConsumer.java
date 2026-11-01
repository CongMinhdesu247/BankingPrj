package com.minh.banking.kafka;

import com.minh.banking.fraud.FraudAnalysisService;
import com.minh.banking.transaction.service.TransactionFraudQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FraudEventConsumer {
    public static final String NAME="fraud-v1";
    private final ProcessedEventRepository receipts;
    private final EventCodec codec;
    private final TransactionFraudQueryService facts;
    private final FraudAnalysisService analysis;
    public FraudEventConsumer(ProcessedEventRepository receipts,EventCodec codec,TransactionFraudQueryService facts,FraudAnalysisService analysis) {
        this.receipts=receipts; this.codec=codec; this.facts=facts; this.analysis=analysis;
    }
    @Transactional(timeout=15)
    public void process(EventEnvelope event) {
        if (event.eventType()!=EventType.TRANSFER_COMPLETED) throw new InvalidEventException();
        var payload=codec.payload(event,TransferCompletedEvent.class);
        var fact=facts.find(payload.transactionId()).orElseThrow(InvalidEventException::new);
        if (!fact.sourceAccountId().equals(payload.sourceAccountId()) || !fact.at().equals(event.occurredAt())) throw new InvalidEventException();
        if (!receipts.claim(NAME,event)) return;
        analysis.analyzeInTransaction(fact.id());
    }
}
