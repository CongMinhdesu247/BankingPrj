package com.minh.banking.transaction.dto.response;

import com.minh.banking.transaction.enums.EntryDirection;
import java.util.UUID;

public record TransactionEntryResponse(UUID id, UUID accountId, EntryDirection direction,
        String amount, String currency,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true, description = "Hidden (null) for a customer's counterparty account")
        String balanceAfter) {}
