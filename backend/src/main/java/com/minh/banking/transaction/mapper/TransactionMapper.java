package com.minh.banking.transaction.mapper;

import com.minh.banking.transaction.dto.response.*;
import com.minh.banking.transaction.entity.*;

public final class TransactionMapper {
    private TransactionMapper() {}

    public static TransactionResponse toResponse(BankTransaction transaction) {
        return new TransactionResponse(transaction.getId(), transaction.getReference(),
            transaction.getSourceAccountId(), transaction.getDestinationAccountId(),
            transaction.getAmount().toPlainString(), transaction.getCurrency(), transaction.getType(),
            transaction.getStatus(), transaction.getDescription(), transaction.getCreatedAt());
    }

    public static TransactionEntryResponse toResponse(TransactionEntry entry) {
        return new TransactionEntryResponse(entry.getId(), entry.getAccountId(), entry.getDirection(),
            entry.getAmount().toPlainString(), entry.getCurrency(), entry.getBalanceAfter().toPlainString());
    }
}
