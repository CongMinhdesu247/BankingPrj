package com.minh.banking.account.service;

import com.minh.banking.account.entity.Account;
import com.minh.banking.common.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal write API. Caller must own the surrounding database transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AccountLockService {
    private final EntityManager entityManager;
    private final long lockTimeoutMs;

    public AccountLockService(EntityManager entityManager,
        @Value("${banking.transfer.lock-timeout-ms:2000}") long lockTimeoutMs) {
        if (lockTimeoutMs < 1 || lockTimeoutMs > 60000) throw new IllegalArgumentException("Invalid account lock timeout");
        this.entityManager = entityManager;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    public Account lockOne(UUID id) {
        configureLockTimeout();
        return loadLocked(id);
    }

    public LockedAccounts lockPair(UUID sourceId, UUID destinationId) {
        if (sourceId.equals(destinationId)) {
            throw new BusinessException("SAME_ACCOUNT", "Source and destination must differ.", HttpStatus.BAD_REQUEST);
        }
        configureLockTimeout();
        // Canonical UUID text has unsigned byte ordering, matching PostgreSQL UUID.
        // UUID.compareTo uses signed longs and is deliberately not used here.
        boolean sourceFirst = sourceId.toString().compareTo(destinationId.toString()) < 0;
        Account first = loadLocked(sourceFirst ? sourceId : destinationId);
        Account second = loadLocked(sourceFirst ? destinationId : sourceId);
        return sourceFirst ? new LockedAccounts(first, second) : new LockedAccounts(second, first);
    }

    public void debit(Account account, BigDecimal amount) {
        requireWriteLock(account);
        account.debit(amount);
    }

    public void credit(Account account, BigDecimal amount) {
        requireWriteLock(account);
        account.credit(amount);
    }

    public void requireWriteLock(Account account) {
        if (!entityManager.contains(account) || entityManager.getLockMode(account) != LockModeType.PESSIMISTIC_WRITE) {
            throw new IllegalStateException("Account mutation requires the account write lock");
        }
    }

    private void configureLockTimeout() {
        // SET LOCAL semantics: applies only to this transaction, not the pooled connection's next user.
        entityManager.createNativeQuery("select set_config('lock_timeout', :timeout, true)")
        .setParameter("timeout", lockTimeoutMs + "ms").getSingleResult();
    }

    private Account loadLocked(UUID id) {
        try {
            Account account = entityManager.getReference(Account.class, id);
            // Refresh WITH the lock, rather than querying a stale managed entity with
            // a lock first (Hibernate can reject its old @Version before refreshing).
            entityManager.refresh(account, LockModeType.PESSIMISTIC_WRITE);
            return account;
        } catch (jakarta.persistence.EntityNotFoundException exception) {
            throw new BusinessException("ACCOUNT_NOT_FOUND", "Account was not found.", HttpStatus.NOT_FOUND);
        }
    }

    public record LockedAccounts(Account source, Account destination) {}
}
