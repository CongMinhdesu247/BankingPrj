package com.minh.banking.security;

import com.minh.banking.account.service.AccountService;
import com.minh.banking.common.exception.*;
import com.minh.banking.transaction.dto.response.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class OwnershipService {
    private final CurrentUser current;
    private final AccountService accounts;
    public OwnershipService(CurrentUser current, AccountService accounts) { this.current = current; this.accounts = accounts; }

    public void requireCustomer(UUID customerId, ErrorCode missing) {
        var principal = current.get();
        if (principal.isCustomer() && !Objects.equals(principal.customerId(), customerId)) throw hidden(missing);
    }

    public void requireTransferSource(UUID customerId) {
        var principal = current.get();
        if (!principal.isCustomer()) throw new BusinessException(ErrorCode.FORBIDDEN, "Access is denied.");
        if (!Objects.equals(principal.customerId(), customerId)) throw hidden(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    public TransactionDetailResponse filterDetail(TransactionDetailResponse detail) {
        var principal = current.get();
        if (!principal.isCustomer()) return detail;
        Set<UUID> owned = new HashSet<>();
        var transaction = detail.transaction();
        for (UUID accountId : List.of(transaction.sourceAccountId(), transaction.destinationAccountId())) {
            if (Objects.equals(accounts.get(accountId).customerId(), principal.customerId())) owned.add(accountId);
        }
        if (owned.isEmpty()) throw hidden(ErrorCode.TRANSACTION_NOT_FOUND);
        return redact(detail, owned);
    }

    public TransactionDetailResponse redact(TransactionDetailResponse detail, Set<UUID> owned) {
        if (!current.get().isCustomer()) return detail;
        return new TransactionDetailResponse(detail.transaction(), detail.entries().stream().map(entry ->
            new TransactionEntryResponse(entry.id(), entry.accountId(), entry.direction(), entry.amount(), entry.currency(),
                owned.contains(entry.accountId()) ? entry.balanceAfter() : null)).toList());
    }

    public static BusinessException hidden(ErrorCode code) {
        String message = switch (code) {
            case ACCOUNT_NOT_FOUND -> "Account was not found.";
            case TRANSACTION_NOT_FOUND -> "Transaction was not found.";
            case CUSTOMER_NOT_FOUND -> "Customer was not found.";
            default -> "Resource was not found.";
        };
        return new BusinessException(code, message);
    }
}
