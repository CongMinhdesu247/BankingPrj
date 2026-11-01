package com.minh.banking.transaction.dto.response;

import java.util.List;

public record TransactionDetailResponse(TransactionResponse transaction, List<TransactionEntryResponse> entries) {}
