package com.minh.banking.security;

import com.minh.banking.user.enums.RoleCode;
import java.util.*;

public record BankingPrincipal(UUID id, String username, UUID customerId, Set<RoleCode> roles) implements java.security.Principal {
    public BankingPrincipal { roles = Set.copyOf(roles); }
    public boolean isCustomer() { return roles.contains(RoleCode.CUSTOMER); }
    @Override public String getName() { return id.toString(); }
}
