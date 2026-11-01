package com.minh.banking.account.dto.request;

import com.minh.banking.account.enums.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeAccountStatusRequest(@NotNull AccountStatus status) {}
