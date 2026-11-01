package com.minh.banking.customer.controller;

import com.minh.banking.account.dto.response.AccountResponse;
import com.minh.banking.account.service.AccountService;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.customer.dto.response.CustomerResponse;
import com.minh.banking.customer.service.CustomerService;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Read-only investigation surface; existing customer mutation permissions stay unchanged. */
@RestController @RequestMapping("/api/v1/staff/customers") @PreAuthorize("hasAnyRole('BANK_STAFF','RISK_OFFICER','ADMIN')")
public class StaffCustomerController {
    private final CustomerService customers;
    private final AccountService accounts;
    public StaffCustomerController(CustomerService customers,AccountService accounts) { this.customers=customers; this.accounts=accounts; }
    @GetMapping public PageResponse<CustomerResponse> list(@RequestParam(defaultValue="0") @Min(0) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(defaultValue="") @Size(max=150) String search) {
        return customers.list(page,size,search);
    }
    @GetMapping("/{id}") public CustomerResponse get(@PathVariable UUID id) { return customers.get(id); }
    @GetMapping("/{id}/accounts") public PageResponse<AccountResponse> accounts(@PathVariable UUID id,
        @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return accounts.list(id,page,size);
    }
}
