package com.minh.banking.audit;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class FailureAuditWriter {
    private final AuditService audit;
    private final com.minh.banking.transaction.service.TransferAttemptService attempts;
    public FailureAuditWriter(AuditService audit,com.minh.banking.transaction.service.TransferAttemptService attempts) { this.audit = audit; this.attempts=attempts; }
    // Caller invokes this only after the monetary transaction has rolled back.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transfer(UUID actor, UUID source, String code) {
        audit.write(actor, AuditService.Action.TRANSFER_FAILED, "ACCOUNT", source, false, Map.of("code", code));
        attempts.failed(actor,source,code);
    }
}
