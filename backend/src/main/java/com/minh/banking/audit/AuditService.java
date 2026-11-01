package com.minh.banking.audit;

import com.minh.banking.security.CurrentUser;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Only explicit, whitelisted metadata is passed here; never request bodies or credentials. */
@Service
public class AuditService {
    public enum Action {
        PASSWORD_CHANGED, PASSWORD_RESET, MFA_ENABLED, MFA_DISABLED, SESSION_REVOKED,
        TEMPLATE_CREATED, TEMPLATE_DELETED, TICKET_CREATED, TICKET_REPLIED, TICKET_UPDATED, LIMIT_REQUESTED, LIMIT_REVIEWED,
        CUSTOMER_CREATED, CUSTOMER_UPDATED, ACCOUNT_OPENED, ACCOUNT_STATUS_CHANGED,
        USER_CREATED, USER_ROLES_CHANGED, USER_STATUS_CHANGED, TRANSFER_COMPLETED,
        TRANSFER_FAILED, TRANSFER_LIMIT_UPDATED, BENEFICIARY_CREATED,
        BENEFICIARY_UPDATED, BENEFICIARY_DELETED, LOGIN_SUCCEEDED, LOGIN_FAILED,
        FRAUD_RULE_UPDATED, FRAUD_ALERT_ASSIGNED, FRAUD_ALERT_RESOLVED
    }
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final CurrentUser current;
    public AuditService(JdbcTemplate jdbc, JsonMapper json, Clock clock, CurrentUser current) {
        this.jdbc = jdbc; this.json = json; this.clock = clock; this.current = current;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void success(Action action, String type, UUID id, Map<String, ?> details) {
        write(current.get().id(), action, type, id, true, details);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID actor, Action action, String type, UUID id, boolean success, Map<String, ?> details) {
        jdbc.update("""
            INSERT INTO audit_logs(id,actor_id,action,target_type,target_id,outcome,request_id,details,created_at)
            VALUES (?,?,?,?,?,?,?,cast(? as jsonb),?)
            """, UUID.randomUUID(), actor, action.name(), type, id == null ? null : id.toString(),
            success ? "SUCCESS" : "FAILURE", MDC.get("requestId"), json.writeValueAsString(details), Timestamp.from(clock.instant()));
    }
}
