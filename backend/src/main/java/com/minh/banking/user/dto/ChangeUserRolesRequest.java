package com.minh.banking.user.dto;

import com.minh.banking.user.enums.RoleCode;
import jakarta.validation.constraints.*;
import java.util.Set;

public record ChangeUserRolesRequest(@NotNull @Size(min = 1, max = 4) Set<@NotNull RoleCode> roles) {}
