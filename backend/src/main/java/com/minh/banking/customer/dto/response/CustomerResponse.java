package com.minh.banking.customer.dto.response;

import com.minh.banking.customer.enums.CustomerStatus;
import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(UUID id, String customerCode, String fullName,
        String email, String phone, CustomerStatus status, Instant createdAt, Instant updatedAt) {}
