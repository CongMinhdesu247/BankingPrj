package com.minh.banking.kafka;
import java.util.UUID;
public record FraudDetectedEvent(UUID alertId,UUID transactionId,UUID sourceAccountId) {}
