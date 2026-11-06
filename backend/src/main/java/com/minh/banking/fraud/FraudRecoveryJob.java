package com.minh.banking.fraud;

import java.util.UUID;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import com.minh.banking.transaction.service.TransferCommitted;

@Component
public class FraudRecoveryJob {
    private static final Logger LOG = LoggerFactory.getLogger(FraudRecoveryJob.class);
    private final JdbcTemplate jdbc;
    private final FraudAnalysisService analysis;
    private final boolean enabled;
    public FraudRecoveryJob(JdbcTemplate jdbc, FraudAnalysisService analysis, @Value("${banking.fraud.recovery-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.analysis = analysis;
        this.enabled = enabled;
    }
    @Scheduled(fixedDelayString="${banking.fraud.recovery-delay-ms:30000}", initialDelayString="${banking.fraud.recovery-initial-delay-ms:10000}")
    public void scheduled() {
        if (enabled) recover();
    }
    public int recover() {
        int completed = 0;
        try {
            var pending = jdbc.queryForList("""
                SELECT t.id FROM transactions t WHERE t.type='INTERNAL_TRANSFER' AND t.status='COMPLETED'
                AND NOT EXISTS (SELECT 1 FROM fraud_analysis_runs r WHERE r.transaction_id=t.id AND r.analysis_version=?)
                ORDER BY t.created_at,t.id LIMIT 100
                """, UUID.class, FraudAnalysisService.ANALYSIS_VERSION);
            for (UUID id:pending) if (attempt(id)) completed++;
        } catch (RuntimeException failure) {
            log(failure);
        }
        return completed;
    }
    public boolean attempt(UUID id) {
        try {
            return analysis.analyze(id);
        }
        catch (RuntimeException failure) {
            log(failure);
            return false;
        }
    }
    private void log(RuntimeException failure) {
        LOG.warn("Fraud analysis deferred for recovery: type={}, requestId={}", failure.getClass().getSimpleName(), MDC.get("requestId"));
    }
}
