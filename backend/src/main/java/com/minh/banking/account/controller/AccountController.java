package com.minh.banking.account.controller;

import com.minh.banking.account.dto.request.*;
import com.minh.banking.account.dto.response.AccountResponse;
import com.minh.banking.account.service.AccountService;
import com.minh.banking.common.response.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AccountController {
    private final AccountService service;
    private final com.minh.banking.security.OwnershipService ownership;

    public AccountController(AccountService service, com.minh.banking.security.OwnershipService ownership) {
        this.service = service; this.ownership = ownership;
    }

    @PostMapping("/customers/{customerId}/accounts")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('BANK_STAFF','ADMIN')")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Account created with zero VND balance")
    public ResponseEntity<AccountResponse> create(@PathVariable UUID customerId,
            @Valid @RequestBody CreateAccountRequest request) {
        AccountResponse account = service.create(customerId);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id())).body(account);
    }

    @GetMapping("/customers/{customerId}/accounts")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('CUSTOMER','BANK_STAFF','ADMIN')")
    public PageResponse<AccountResponse> list(@PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        ownership.requireCustomer(customerId, com.minh.banking.common.exception.ErrorCode.CUSTOMER_NOT_FOUND);
        return service.list(customerId, page, size);
    }

    @GetMapping("/accounts/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('CUSTOMER','BANK_STAFF','RISK_OFFICER','ADMIN')")
    public AccountResponse get(@PathVariable UUID id) {
        var account = service.get(id);
        ownership.requireCustomer(account.customerId(), com.minh.banking.common.exception.ErrorCode.ACCOUNT_NOT_FOUND);
        return account;
    }

    @PatchMapping("/accounts/{id}/status")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('BANK_STAFF','ADMIN')")
    public AccountResponse changeStatus(@PathVariable UUID id,
            @Valid @RequestBody ChangeAccountStatusRequest request) {
        return service.changeStatus(id, request);
    }
}
