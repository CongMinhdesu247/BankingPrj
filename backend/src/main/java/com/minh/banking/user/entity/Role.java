package com.minh.banking.user.entity;

import com.minh.banking.user.enums.RoleCode;
import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name = "roles")
public class Role {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, unique = true, length = 30)
    private RoleCode code;
    @Column(length = 255) private String description;
    protected Role() {}
    public UUID getId() { return id; }
    public RoleCode getCode() { return code; }
}
