package com.minh.banking.account.entity;

import com.minh.banking.account.enums.AccountStatus;
import com.minh.banking.customer.entity.Customer;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Column(name = "account_number", nullable = false, unique = true, length = 30, updatable = false)
    private String accountNumber;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {}

    public Account(Customer customer, String accountNumber) {
        this.customer = customer;
        this.accountNumber = accountNumber;
        this.currency = "VND";
        this.balance = new BigDecimal("0.00");
        this.status = AccountStatus.ACTIVE;
    }

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public void changeStatus(AccountStatus status) { this.status = status; }

    // Internal domain mutations; application callers use AccountLockService, never a balance setter.
    public void debit(BigDecimal amount) {
        requireWholePositiveVnd(amount);
        if (balance.compareTo(amount) < 0) throw new IllegalArgumentException("Insufficient account balance");
        balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        requireWholePositiveVnd(amount);
        BigDecimal result = balance.add(amount);
        if (result.compareTo(new BigDecimal("99999999999999999.00")) > 0) {
            throw new IllegalArgumentException("Account balance exceeds supported precision");
        }
        balance = result;
    }

    private static void requireWholePositiveVnd(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.remainder(BigDecimal.ONE).signum() != 0) {
            throw new IllegalArgumentException("Amount must be positive whole VND with scale at most 2");
        }
    }
    public UUID getId() { return id; }
    public UUID getCustomerId() { return customer.getId(); }
    public String getAccountNumber() { return accountNumber; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalance() { return balance; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
