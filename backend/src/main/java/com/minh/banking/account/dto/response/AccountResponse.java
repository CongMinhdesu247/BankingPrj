package com.minh.banking.account.dto.response;

import com.minh.banking.account.enums.AccountStatus;
import java.time.Instant;
import java.util.UUID;

// Money is a decimal string so JavaScript clients never lose precision.
public record AccountResponse(UUID id, UUID customerId, String accountNumber, String currency,
        String balance, AccountStatus status, Instant createdAt, Instant updatedAt) {}
