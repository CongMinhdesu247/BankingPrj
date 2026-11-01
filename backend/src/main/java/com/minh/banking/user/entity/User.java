package com.minh.banking.user.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity @Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, unique = true, length = 100, updatable = false)
    private String username;
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "customer_id", unique = true, updatable = false) private UUID customerId;
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected User() {}
    public User(String username, String passwordHash, UUID customerId, Set<Role> roles) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.customerId = customerId;
        this.enabled = true;
        this.roles = new HashSet<>(roles);
    }
    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEnabled() { return enabled; }
    public UUID getCustomerId() { return customerId; }
    public Set<Role> getRoles() { return Collections.unmodifiableSet(roles); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void changeRoles(Set<Role> roles) { this.roles = new HashSet<>(roles); }
    public void changeEnabled(boolean enabled) { this.enabled = enabled; }
}
