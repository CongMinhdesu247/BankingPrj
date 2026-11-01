package com.minh.banking.user.dto;

import com.minh.banking.user.enums.RoleCode;
import jakarta.validation.constraints.*;
import java.util.*;
import io.swagger.v3.oas.annotations.media.Schema;

public record CreateUserRequest(
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{2,99}") String username,
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, format = "password")
    @NotBlank @Size(min = 12, max = 128) String password,
    UUID customerId,
    @NotNull @Size(min = 1, max = 4) Set<@NotNull RoleCode> roles
) {
    public CreateUserRequest {
        username = username == null ? null : username.strip().toLowerCase(Locale.ROOT);
    }
}
