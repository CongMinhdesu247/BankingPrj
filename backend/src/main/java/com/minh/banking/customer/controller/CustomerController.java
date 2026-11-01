package com.minh.banking.customer.controller;

import com.minh.banking.common.response.PageResponse;
import com.minh.banking.customer.dto.request.*;
import com.minh.banking.customer.dto.response.CustomerResponse;
import com.minh.banking.customer.service.CustomerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('BANK_STAFF','ADMIN')")
@RequestMapping("/api/v1/customers")
public class CustomerController {
    private final CustomerService service;

    public CustomerController(CustomerService service) { this.service = service; }

    @PostMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Customer created")
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse customer = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/customers/" + customer.id())).body(customer);
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping
    public PageResponse<CustomerResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "") @Size(max = 150) String search) {
        return service.list(page, size, search);
    }

    @PatchMapping("/{id}")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest request) {
        return service.update(id, request);
    }
}
