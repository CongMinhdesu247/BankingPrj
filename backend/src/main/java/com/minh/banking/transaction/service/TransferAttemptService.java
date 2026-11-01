package com.minh.banking.transaction.service;

import com.minh.banking.kafka.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TransferAttemptService {
    public record Fact(UUID id,UUID actorId,UUID transactionId,UUID sourceAccountId,String outcome,String reasonCode,Instant at,String correlationId) {}
    public record Failed(UUID attemptId) {}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    public TransferAttemptService(JdbcTemplate jdbc,Clock clock,ApplicationEventPublisher events) { this.jdbc=jdbc; this.clock=clock; this.events=events; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void success(UUID transaction,UUID source,UUID actor) { insert(actor,source,transaction,"SUCCESS",null); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void failed(UUID actor,UUID source,String code) {
        boolean system=Set.of("TRANSFER_PROCESSING_ERROR","ACCOUNT_BUSY","IDEMPOTENCY_BUSY").contains(code);
        if (!system && !Set.of("INSUFFICIENT_FUNDS","ACCOUNT_NOT_ACTIVE","CURRENCY_MISMATCH","BALANCE_LIMIT_EXCEEDED",
                "PER_TRANSFER_LIMIT_EXCEEDED","DAILY_LIMIT_EXCEEDED","IDEMPOTENCY_CONFLICT").contains(code)) return;
        UUID attempt=insert(actor,source,null,system?"SYSTEM_FAILURE":"BUSINESS_FAILURE",code);
        events.publishEvent(new Failed(attempt));
    }
    private UUID insert(UUID actor,UUID source,UUID transaction,String outcome,String code) {
        UUID id=UUID.randomUUID(); String request=MDC.get("requestId"); String correlation=request==null?id.toString():request;
        jdbc.update("""
            INSERT INTO transfer_attempts(attempt_id,actor_id,transaction_id,source_account_id,request_id,correlation_id,outcome,reason_code,created_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,id,actor,transaction,source,request,correlation,outcome,code,Timestamp.from(clock.instant()));
        return id;
    }
    @Transactional(readOnly=true)
    public Optional<Fact> find(UUID id) {
        return jdbc.query("SELECT * FROM transfer_attempts WHERE attempt_id=?",(rs,n)->new Fact(rs.getObject("attempt_id",UUID.class),
            rs.getObject("actor_id",UUID.class),rs.getObject("transaction_id",UUID.class),rs.getObject("source_account_id",UUID.class),
            rs.getString("outcome"),rs.getString("reason_code"),rs.getTimestamp("created_at").toInstant(),rs.getString("correlation_id")),id).stream().findFirst();
    }
}
