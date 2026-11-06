package com.minh.banking.auth.service;

import com.minh.banking.auth.dto.*;
import com.minh.banking.common.exception.*;
import com.minh.banking.audit.LoginAuditWriter;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private static final Logger LOG=LoggerFactory.getLogger(AuthService.class);
    private final AuthVerificationService verification;
    private final LoginAuditWriter history;
    private final LoginAttemptService attempts;
    public AuthService(AuthVerificationService verification,LoginAuditWriter history,LoginAttemptService attempts) {
        this.verification=verification; this.history=history; this.attempts=attempts;
    }
    public TokenResponse login(LoginRequest request) {
        attempts.check(request.username());
        TokenResponse result;
        try { result=verification.verify(request); }
        catch (BusinessException failure) {
            if (ErrorCode.MFA_REQUIRED.name().equals(failure.getCode())) {
                throw failure;
            }
            // Verification's read transaction has ended before this durable write.
            try { history.record(request.username(),null,false); }
            catch (RuntimeException unavailable) {
                LOG.error("Login failure audit unavailable: type={}, requestId={}",unavailable.getClass().getSimpleName(),MDC.get("requestId"));
            }
            throw failure;
        }
        history.record(request.username(),result.user().id(),true);
        attempts.succeeded(request.username());
        return result;
    }
}
