package com.minh.banking.kafka;
import java.util.UUID;
public record TransferFailedEvent(UUID attemptId,UUID actorId,UUID sourceAccountId,String outcome,String reasonCode) {}
