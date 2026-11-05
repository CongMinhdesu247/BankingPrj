package com.minh.banking.auth.dto;

import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;

public record RegisterRequest(
    @NotBlank @Size(min=2,max=150) String fullName,
    @NotBlank @Email @Size(max=254) String email,
    @Pattern(regexp="\\+?[0-9]{8,15}") String phone,
    @NotBlank @Pattern(regexp="[a-z0-9][a-z0-9._-]{2,99}") String username,
    @Schema(accessMode=Schema.AccessMode.WRITE_ONLY,format="password")
    @NotBlank @Size(min=12,max=128) String password
) {
    public RegisterRequest {
        fullName=fullName==null?null:fullName.strip();
        email=email==null?null:email.strip().toLowerCase(Locale.ROOT);
        phone=phone==null||phone.isBlank()?null:phone.strip();
        username=username==null?null:username.strip().toLowerCase(Locale.ROOT);
    }
    @Override public String toString() { return "RegisterRequest[REDACTED]"; }
}
