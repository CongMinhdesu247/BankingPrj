package com.minh.banking.fraud;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/fraud-rules") @PreAuthorize("hasRole('ADMIN')")
public class FraudRuleController {
    private final FraudRuleService service;
    public FraudRuleController(FraudRuleService service) {
        this.service = service;
    }
    @GetMapping public List<FraudRule> list() {
        return service.list();
    }
    @PatchMapping("/{id}") public FraudRule update(@PathVariable UUID id, @Valid @RequestBody FraudRuleService.Update request) {
        return service.update(id, request);
    }
}
