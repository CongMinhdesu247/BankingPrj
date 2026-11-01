package com.minh.banking.fraud;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FraudContext(UUID transactionId,UUID sourceAccountId,UUID destinationAccountId,
        UUID sourceCustomerId,UUID actorId,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal amount,Instant occurredAt,long velocityCount,
        Instant beneficiaryCreatedAt,long failedLoginCount) {}
