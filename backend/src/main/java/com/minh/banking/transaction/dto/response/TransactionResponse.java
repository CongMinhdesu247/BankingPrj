package com.minh.banking.transaction.dto.response;

import com.minh.banking.transaction.enums.*;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(UUID id, String reference, UUID sourceAccountId, UUID destinationAccountId,
        String amount, String currency, TransactionType type, TransactionStatus status,
        String description, Instant createdAt) {}
