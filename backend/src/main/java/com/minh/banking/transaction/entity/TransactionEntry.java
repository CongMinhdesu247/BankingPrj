package com.minh.banking.transaction.entity;

import com.minh.banking.account.entity.Account;
import com.minh.banking.transaction.enums.EntryDirection;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "transaction_entries", uniqueConstraints = @UniqueConstraint(columnNames = {"transaction_id", "direction"}))
public class TransactionEntry {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private BankTransaction transaction;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 6)
    private EntryDirection direction;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TransactionEntry() {}

    public TransactionEntry(BankTransaction transaction, Account account, EntryDirection direction) {
        this.transaction = transaction;
        this.account = account;
        this.direction = direction;
        this.amount = transaction.getAmount();
        this.currency = transaction.getCurrency();
        this.balanceAfter = account.getBalance();
        this.createdAt = transaction.getCreatedAt();
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return account.getId(); }
    public EntryDirection getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public Instant getCreatedAt() { return createdAt; }
}
