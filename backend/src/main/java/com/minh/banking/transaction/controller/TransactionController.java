package com.minh.banking.transaction.controller;

import com.minh.banking.common.response.PageResponse;
import com.minh.banking.transaction.dto.response.*;
import com.minh.banking.transaction.service.TransactionQueryService;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('CUSTOMER','BANK_STAFF','RISK_OFFICER','ADMIN')")
@RequestMapping("/api/v1")
public class TransactionController {
    private final TransactionQueryService service;
    private final com.minh.banking.security.OwnershipService ownership;
    private final com.minh.banking.account.service.AccountService accounts;
    public TransactionController(TransactionQueryService service, com.minh.banking.security.OwnershipService ownership,
            com.minh.banking.account.service.AccountService accounts) {
        this.service = service; this.ownership = ownership; this.accounts = accounts;
    }

    @GetMapping("/transactions/{id}")
    public TransactionDetailResponse get(@PathVariable UUID id) { return ownership.filterDetail(service.get(id)); }

    @GetMapping("/accounts/{id}/transactions")
    public PageResponse<TransactionResponse> history(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        ownership.requireCustomer(accounts.get(id).customerId(), com.minh.banking.common.exception.ErrorCode.ACCOUNT_NOT_FOUND);
        return service.history(id, page, size, from, to);
    }
}
