package com.minh.banking.transaction.service;

import com.minh.banking.account.entity.Account;
import com.minh.banking.account.enums.AccountStatus;
import com.minh.banking.account.service.AccountLockService;
import com.minh.banking.common.exception.BusinessException;
import com.minh.banking.transaction.dto.request.TransferRequest;
import com.minh.banking.transaction.dto.response.TransactionDetailResponse;
import com.minh.banking.transaction.entity.*;
import com.minh.banking.transaction.enums.*;
import com.minh.banking.transaction.mapper.TransactionMapper;
import com.minh.banking.transaction.repository.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TransferService {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.00");
    private final AccountLockService accounts;
    private final BankTransactionRepository transactions;
    private final TransactionLedgerWriter ledger;
    private final com.minh.banking.security.OwnershipService ownership;
    private final com.minh.banking.security.CurrentUser current;
    private final com.minh.banking.account.service.AccountService accountQueries;
    private final com.minh.banking.idempotency.IdempotencyService idempotency;
    private final com.minh.banking.limit.TransferLimitService limits;
    private final com.minh.banking.audit.AuditService audit;
    private final java.time.Clock clock;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final TransferAttemptService attempts;

    public TransferService(AccountLockService accounts, BankTransactionRepository transactions,
            TransactionLedgerWriter ledger, com.minh.banking.security.OwnershipService ownership,
            com.minh.banking.security.CurrentUser current,
            com.minh.banking.account.service.AccountService accountQueries,
            com.minh.banking.idempotency.IdempotencyService idempotency,
            com.minh.banking.limit.TransferLimitService limits,
            com.minh.banking.audit.AuditService audit, java.time.Clock clock,
            org.springframework.context.ApplicationEventPublisher events,TransferAttemptService attempts) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.ledger = ledger;
        this.ownership = ownership;
        this.current = current;
        this.accountQueries=accountQueries; this.idempotency=idempotency;
        this.limits=limits; this.audit=audit; this.clock=clock;
        this.events=events;
        this.attempts=attempts;
    }

    public record Result(TransactionDetailResponse response, boolean replayed) {}

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 15)
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('CUSTOMER')")
    public Result transfer(String key, TransferRequest request) {
        BigDecimal amount = validate(request);
        // Ownership is checked even for replay. Account ownership is immutable.
        ownership.requireTransferSource(accountQueries.get(request.sourceAccountId()).customerId());
        var claim = idempotency.claim(current.get().id(), key, request, amount);
        if (claim.replay()!=null) return new Result(claim.replay(),true);
        var locked = accounts.lockPair(request.sourceAccountId(), request.destinationAccountId());
        Account source = locked.source();
        Account destination = locked.destination();
        ownership.requireTransferSource(source.getCustomerId());
        // All business decisions below use state refreshed AFTER both row locks.
        if (source.getStatus() != AccountStatus.ACTIVE || destination.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException("ACCOUNT_NOT_ACTIVE", "Both accounts must be active.", HttpStatus.CONFLICT);
        }
        if (!"VND".equals(source.getCurrency()) || !source.getCurrency().equals(destination.getCurrency())) {
            throw new BusinessException("CURRENCY_MISMATCH", "Both accounts must use VND.", HttpStatus.CONFLICT);
        }
        if (source.getBalance().compareTo(amount) < 0) {
            throw new BusinessException("INSUFFICIENT_FUNDS", "Source account has insufficient funds.", HttpStatus.CONFLICT);
        }
        if (destination.getBalance().add(amount).compareTo(MAX_BALANCE) > 0) {
            throw new BusinessException("BALANCE_LIMIT_EXCEEDED", "Destination balance exceeds supported precision.", HttpStatus.CONFLICT);
        }
        var now=clock.instant(); // One post-lock timestamp for policy day and persisted transfer.
        limits.checkLocked(source,amount,now);
        accounts.debit(source, amount);
        accounts.credit(destination, amount);
        var transaction = transactions.saveAndFlush(new BankTransaction(source, destination, amount,
            TransactionType.INTERNAL_TRANSFER, request.description(), now,current.get().id()));
        var debit = ledger.record(transaction, source, EntryDirection.DEBIT);
        var credit = ledger.record(transaction, destination, EntryDirection.CREDIT);
        var response = new TransactionDetailResponse(TransactionMapper.toResponse(transaction),
            List.of(TransactionMapper.toResponse(debit), TransactionMapper.toResponse(credit)));
        var owned = new java.util.HashSet<java.util.UUID>();
        owned.add(source.getId());
        if (java.util.Objects.equals(destination.getCustomerId(), current.get().customerId())) owned.add(destination.getId());
        response=ownership.redact(response, owned);
        audit.success(com.minh.banking.audit.AuditService.Action.TRANSFER_COMPLETED,"TRANSACTION",transaction.getId(),
            java.util.Map.of("sourceAccountId",source.getId(),"destinationAccountId",destination.getId()));
        idempotency.complete(claim.id(),response);
        attempts.success(transaction.getId(),source.getId(),current.get().id());
        events.publishEvent(new TransferCommitted(transaction.getId(),source.getId(),now));
        return new Result(response,false);
    }

    private BigDecimal validate(TransferRequest request) {
        if (request == null || request.sourceAccountId() == null || request.destinationAccountId() == null
                || request.amount() == null || !request.amount().matches("[0-9]{1,17}(?:\\.0{1,2})?")
                || !"VND".equals(request.currency())
                || (request.description() != null && request.description().length() > 255)) {
            throw new BusinessException("INVALID_TRANSFER", "Invalid transfer input.", HttpStatus.BAD_REQUEST);
        }
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new BusinessException("SAME_ACCOUNT", "Source and destination must differ.", HttpStatus.BAD_REQUEST);
        }
        BigDecimal amount = new BigDecimal(request.amount());
        if (amount.signum() <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Amount must be positive whole VND.", HttpStatus.BAD_REQUEST);
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
