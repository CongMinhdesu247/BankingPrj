package com.minh.banking.transaction.service;

import com.minh.banking.audit.FailureAuditWriter;
import com.minh.banking.common.exception.BusinessException;
import com.minh.banking.security.CurrentUser;
import com.minh.banking.transaction.dto.request.TransferRequest;
import org.slf4j.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** No transaction here: failure recording starts only after the core proxy has rolled back. */
@Service
public class TransferFacade {
    private static final Logger LOG=LoggerFactory.getLogger(TransferFacade.class);
    private final TransferService transfers;
    private final FailureAuditWriter failures;
    private final CurrentUser current;
    public TransferFacade(TransferService transfers, FailureAuditWriter failures, CurrentUser current) {
        this.transfers=transfers; this.failures=failures; this.current=current;
    }
    @PreAuthorize("hasRole('CUSTOMER')")
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NEVER)
    public TransferService.Result transfer(String key, TransferRequest request) {
        try { return transfers.transfer(key,request); }
        catch (RuntimeException failure) {
            try { failures.transfer(current.get().id(),request.sourceAccountId(),
                failure instanceof BusinessException business?business.getCode():"TRANSFER_PROCESSING_ERROR"); }
            catch (RuntimeException unavailable) {
                LOG.error("Failure audit unavailable: type={}, requestId={}",unavailable.getClass().getSimpleName(),MDC.get("requestId"));
            }
            throw failure;
        }
    }
}
