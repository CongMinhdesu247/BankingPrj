package com.minh.banking.customer.entity;

import com.minh.banking.customer.enums.CustomerStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_code", nullable = false, unique = true, length = 30, updatable = false)
    private String customerCode;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(unique = true, length = 254)
    private String email;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {}

    public Customer(String customerCode, String fullName, String email, String phone) {
        this.customerCode = customerCode;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.status = CustomerStatus.ACTIVE;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void changeEmail(String email) { this.email = email; }
    public void changePhone(String phone) { this.phone = phone; }
    public UUID getId() { return id; }
    public String getCustomerCode() { return customerCode; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public CustomerStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
