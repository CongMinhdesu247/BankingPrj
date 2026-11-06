package com.minh.banking.auth.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;
import io.swagger.v3.oas.annotations.media.Schema;

public record LoginRequest(@NotBlank @Size(max = 100) String username,
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, format = "password")
    @NotBlank @Size(max = 128) String password,
    @Size(max=80) String otp) {
    public LoginRequest(String username, String password) {
        this(username, password, null);
    }
    public LoginRequest {
        username = username == null ? null : username.strip().toLowerCase(Locale.ROOT);
    }
    @Override public String toString() {
        return "LoginRequest[credentials=REDACTED]";
    }
}
