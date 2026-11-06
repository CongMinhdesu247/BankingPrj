package com.minh.banking.fraud;

import com.minh.banking.common.response.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1") @PreAuthorize("hasRole('RISK_OFFICER')")
public class FraudAlertController {
    private final FraudAlertService service;
    public FraudAlertController(FraudAlertService service) {
        this.service = service;
    }
    @GetMapping("/fraud/summary") public FraudAlertService.Summary summary() {
        return service.summary();
    }
    @GetMapping("/fraud/officers") public List<FraudAlertService.Officer> officers() {
        return service.officers();
    }
    @GetMapping("/fraud-alerts") public PageResponse<FraudAlert> list(@RequestParam(defaultValue="0") @Min(0) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int size, @RequestParam(required=false) FraudAlert.Status status,
        @RequestParam(required=false) RiskLevel riskLevel, @RequestParam(required=false) UUID transactionId) {
        return service.list(page, size, status, riskLevel, transactionId);
    }
    @GetMapping("/fraud-alerts/{id}") public FraudAlertService.Detail get(@PathVariable UUID id) {
        return service.detail(id);
    }
    @PatchMapping("/fraud-alerts/{id}/assignment") public FraudAlertService.Detail assign(@PathVariable UUID id, @Valid @RequestBody FraudAlertService.Assignment request) {
        return service.assign(id, request);
    }
    @PatchMapping("/fraud-alerts/{id}/resolution") public FraudAlertService.Detail resolve(@PathVariable UUID id, @Valid @RequestBody FraudAlertService.Resolution request) {
        return service.resolve(id, request);
    }
}
