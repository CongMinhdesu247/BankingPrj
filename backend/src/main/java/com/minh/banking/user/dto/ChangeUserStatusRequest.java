package com.minh.banking.user.dto;

import jakarta.validation.constraints.NotNull;

public record ChangeUserStatusRequest(@NotNull Boolean enabled) {}
