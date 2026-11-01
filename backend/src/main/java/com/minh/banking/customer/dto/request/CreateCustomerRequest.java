package com.minh.banking.customer.dto.request;

import jakarta.validation.constraints.*;

public record CreateCustomerRequest(
    @NotBlank @Size(max = 30) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,29}") String customerCode,
    @NotBlank @Size(max = 150) String fullName,
    @Email @Size(max = 254) String email,
    @Size(max = 20) @Pattern(regexp = "^$|\\+?[0-9]{8,15}") String phone
) {}
