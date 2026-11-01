package com.minh.banking.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(@NotBlank @Pattern(regexp = "VND") String currency) {}
