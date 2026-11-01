package com.minh.banking.limit;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class TransferLimitController {
    private final TransferLimitService service;
    public TransferLimitController(TransferLimitService service) { this.service=service; }
    @GetMapping("/me/transfer-limits")
    public TransferLimitService.LimitResponse mine(@RequestParam UUID accountId) { return service.mine(accountId); }
    @PutMapping("/accounts/{id}/transfer-limit")
    public TransferLimitService.LimitResponse update(@PathVariable UUID id,@Valid @RequestBody UpdateLimitRequest request) {
        return service.update(id,request);
    }
}
