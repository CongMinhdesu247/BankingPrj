package com.minh.banking.fraud;

import java.time.Instant;
import java.util.UUID;

public record FraudAlert(UUID id,UUID transactionId,String reference,String amount,String currency,Instant transactionAt,
        UUID sourceAccountId,UUID destinationAccountId,int analysisVersion,int score,RiskLevel riskLevel,Status status,
        UUID assignedTo,String assignedUsername,String notes,Instant resolvedAt,long version,Instant createdAt,Instant updatedAt) {
    public enum Status { OPEN, IN_REVIEW, CONFIRMED_FRAUD, FALSE_POSITIVE }
    public enum Resolution { CONFIRMED_FRAUD, FALSE_POSITIVE }
}
