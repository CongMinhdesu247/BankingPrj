package com.minh.banking.security;

import com.minh.banking.common.exception.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public BankingPrincipal get() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof BankingPrincipal principal)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication is required.");
        }
        return principal;
    }
}
