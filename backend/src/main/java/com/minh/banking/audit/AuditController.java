package com.minh.banking.audit;

import com.minh.banking.common.response.PageResponse;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/audit-logs")
@PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")
public class AuditController {
    private final AuditQueryService service;
    public AuditController(AuditQueryService service) { this.service=service; }
    @GetMapping
    public PageResponse<AuditQueryService.AuditLog> list(
        @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
        @RequestParam(required=false) UUID actorId,@RequestParam(required=false) AuditService.Action action,
        @RequestParam(required=false) AuditQueryService.Outcome outcome,
        @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to) {
        return service.list(page,size,actorId,action,outcome,from,to);
    }
}
