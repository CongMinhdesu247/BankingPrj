package com.minh.banking.customer.dto.request;

import jakarta.validation.constraints.*;

// null/absent means unchanged; an empty string explicitly clears the contact field.
public record UpdateCustomerRequest(
    @Email @Size(max = 254) String email,
    @Size(max = 20) @Pattern(regexp = "^$|\\+?[0-9]{8,15}") String phone
) {}
