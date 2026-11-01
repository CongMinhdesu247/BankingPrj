package com.minh.banking.transaction.service;

import com.minh.banking.account.service.AccountService;
import com.minh.banking.common.exception.BusinessException;
import com.minh.banking.common.exception.ErrorCode;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.transaction.dto.response.*;
import com.minh.banking.transaction.entity.BankTransaction;
import com.minh.banking.transaction.enums.EntryDirection;
import com.minh.banking.transaction.mapper.TransactionMapper;
import com.minh.banking.transaction.repository.*;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TransactionQueryService {
    private final BankTransactionRepository transactions;
    private final TransactionEntryRepository entries;
    private final AccountService accounts;

    public TransactionQueryService(BankTransactionRepository transactions, TransactionEntryRepository entries,
            AccountService accounts) {
        this.transactions = transactions;
        this.entries = entries;
        this.accounts = accounts;
    }

    public TransactionDetailResponse get(UUID id) {
        BankTransaction transaction = transactions.findById(id).orElseThrow(() -> new BusinessException(
            "TRANSACTION_NOT_FOUND", "Transaction was not found.", HttpStatus.NOT_FOUND));
        var ledger = entries.findAllByTransaction_Id(id).stream()
            .sorted(Comparator.comparingInt(entry -> entry.getDirection() == EntryDirection.DEBIT ? 0 : 1))
            .map(TransactionMapper::toResponse).toList();
        return new TransactionDetailResponse(TransactionMapper.toResponse(transaction), ledger);
    }

    public PageResponse<TransactionResponse> historyForCustomer(UUID customerId, int page, int size, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE, "from must be earlier than to.");
        }
        Specification<BankTransaction> filter = (root, query, builder) -> {
            List<Predicate> conditions = new ArrayList<>();
            conditions.add(builder.or(builder.equal(root.get("sourceAccount").get("customer").get("id"), customerId),
                builder.equal(root.get("destinationAccount").get("customer").get("id"), customerId)));
            if (from != null) conditions.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) conditions.add(builder.lessThan(root.get("createdAt"), to));
            return builder.and(conditions.toArray(Predicate[]::new));
        };
        return PageResponse.from(transactions.findAll(filter, PageRequest.of(page, size,
            Sort.by(Sort.Direction.DESC, "createdAt", "id"))).map(TransactionMapper::toResponse));
    }

    public PageResponse<TransactionResponse> history(UUID accountId, int page, int size, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException("INVALID_TIME_RANGE", "from must be earlier than to.", HttpStatus.BAD_REQUEST);
        }
        accounts.get(accountId);
        Specification<BankTransaction> filter = (root, query, builder) -> {
            List<Predicate> conditions = new ArrayList<>();
            conditions.add(builder.or(builder.equal(root.get("sourceAccount").get("id"), accountId),
                builder.equal(root.get("destinationAccount").get("id"), accountId)));
            if (from != null) conditions.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) conditions.add(builder.lessThan(root.get("createdAt"), to));
            return builder.and(conditions.toArray(Predicate[]::new));
        };
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.from(transactions.findAll(filter, pageable).map(TransactionMapper::toResponse));
    }
}
