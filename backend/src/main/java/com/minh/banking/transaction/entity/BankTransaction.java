package com.minh.banking.transaction.entity;

import com.minh.banking.account.entity.Account;
import com.minh.banking.transaction.enums.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "transactions")
public class BankTransaction {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, unique = true, length = 50)
    private String reference;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false)
    private Account sourceAccount;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false)
    private Account destinationAccount;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private TransactionType type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private TransactionStatus status;
    @Column(length = 255)
    private String description;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "initiated_by")
    private UUID initiatedBy;

    protected BankTransaction() {}

    public BankTransaction(Account source, Account destination, BigDecimal amount,
            TransactionType type, String description) {
        this(source, destination, amount, type, description, Instant.now());
    }

    public BankTransaction(Account source, Account destination, BigDecimal amount,
            TransactionType type, String description, Instant createdAt) {
        this(source,destination,amount,type,description,createdAt,null);
    }
    public BankTransaction(Account source, Account destination, BigDecimal amount,
            TransactionType type, String description, Instant createdAt,UUID initiatedBy) {
        this.initiatedBy=initiatedBy;
        this.reference = (type == TransactionType.OPENING ? "OPN-" : "TRF-") + UUID.randomUUID();
        this.sourceAccount = source;
        this.destinationAccount = destination;
        this.amount = amount;
        this.currency = "VND";
        this.type = type;
        this.status = TransactionStatus.COMPLETED;
        this.description = description;
        this.createdAt = createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public UUID getSourceAccountId() { return sourceAccount.getId(); }
    public UUID getDestinationAccountId() { return destinationAccount.getId(); }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public TransactionType getType() { return type; }
    public TransactionStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}
