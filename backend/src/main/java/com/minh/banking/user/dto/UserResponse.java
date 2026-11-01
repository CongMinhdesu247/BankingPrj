package com.minh.banking.user.dto;

import com.minh.banking.user.enums.RoleCode;
import java.time.Instant;
import java.util.*;

public record UserResponse(UUID id, String username, boolean enabled, UUID customerId,
        Set<RoleCode> roles, Instant createdAt, Instant updatedAt) {}
