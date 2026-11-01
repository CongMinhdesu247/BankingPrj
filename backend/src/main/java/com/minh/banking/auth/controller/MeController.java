package com.minh.banking.auth.controller;

import com.minh.banking.account.dto.response.AccountResponse;
import com.minh.banking.account.service.AccountService;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.customer.dto.response.CustomerResponse;
import com.minh.banking.customer.service.CustomerService;
import com.minh.banking.security.CurrentUser;
import com.minh.banking.transaction.dto.response.TransactionResponse;
import com.minh.banking.transaction.service.TransactionQueryService;
import com.minh.banking.user.dto.UserResponse;
import com.minh.banking.user.service.UserService;
import jakarta.validation.constraints.*;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/me") @PreAuthorize("isAuthenticated()")
public class MeController {
    private final CurrentUser current;
    private final UserService users;
    private final CustomerService customers;
    private final AccountService accounts;
    private final TransactionQueryService transactions;
    private final com.minh.banking.customer.service.CustomerCacheService profiles;
    public MeController(CurrentUser current, UserService users, CustomerService customers,
            AccountService accounts, TransactionQueryService transactions,
            com.minh.banking.customer.service.CustomerCacheService profiles) {
        this.current = current; this.users = users; this.customers = customers; this.accounts = accounts; this.transactions = transactions;
        this.profiles=profiles;
    }
    @GetMapping public MeResponse me() {
        var user = current.get();
        return new MeResponse(users.get(user.id()), user.customerId() == null ? null : profiles.get(user.customerId(),()->customers.get(user.customerId())));
    }
    @GetMapping("/accounts") @PreAuthorize("hasRole('CUSTOMER')")
    public PageResponse<AccountResponse> accounts(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return accounts.list(current.get().customerId(), page, size);
    }
    @GetMapping("/transactions") @PreAuthorize("hasRole('CUSTOMER')")
    public PageResponse<TransactionResponse> transactions(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return transactions.historyForCustomer(current.get().customerId(), page, size, from, to);
    }
    public record MeResponse(UserResponse user, CustomerResponse customer) {}
}
