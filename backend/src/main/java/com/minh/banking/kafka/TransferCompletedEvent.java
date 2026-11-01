package com.minh.banking.kafka;
import java.util.UUID;
public record TransferCompletedEvent(UUID transactionId,UUID sourceAccountId) {}
