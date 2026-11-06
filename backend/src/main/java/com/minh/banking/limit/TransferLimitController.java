package com.minh.banking.limit;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class TransferLimitController {
    private final TransferLimitService service;
    public TransferLimitController(TransferLimitService service) {
        this.service = service;
    }
    @GetMapping("/me/transfer-limits") @PreAuthorize("hasRole('CUSTOMER')")
    public TransferLimitService.LimitResponse mine(@RequestParam UUID accountId) {
        return service.mine(accountId);
    }
    @PutMapping("/accounts/{id}/transfer-limit") @PreAuthorize("hasRole('ADMIN')")
    public TransferLimitService.LimitResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateLimitRequest request) {
        return service.update(id, request);
    }
}
