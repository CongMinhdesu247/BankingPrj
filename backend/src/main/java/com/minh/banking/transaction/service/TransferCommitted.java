package com.minh.banking.transaction.service;

import java.time.Instant;
import java.util.UUID;

public record TransferCommitted(UUID transactionId,UUID sourceAccountId,Instant occurredAt) {}
