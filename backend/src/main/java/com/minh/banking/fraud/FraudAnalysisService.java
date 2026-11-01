package com.minh.banking.fraud;

import com.minh.banking.audit.LoginHistoryQueryService;
import com.minh.banking.beneficiary.BeneficiaryHistoryService;
import com.minh.banking.transaction.service.TransactionFraudQueryService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Service
public class FraudAnalysisService {
    public static final int ANALYSIS_VERSION=1;
    private final JdbcTemplate jdbc;
    private final FraudRuleService rules;
    private final RiskScoreService scores;
    private final TransactionFraudQueryService transactions;
    private final BeneficiaryHistoryService beneficiaries;
    private final LoginHistoryQueryService logins;
    private final JsonMapper json;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher events;
    public FraudAnalysisService(JdbcTemplate jdbc,FraudRuleService rules,RiskScoreService scores,TransactionFraudQueryService transactions,
            BeneficiaryHistoryService beneficiaries,LoginHistoryQueryService logins,JsonMapper json,Clock clock,
            org.springframework.context.ApplicationEventPublisher events) {
        this.jdbc=jdbc; this.rules=rules; this.scores=scores; this.transactions=transactions;
        this.beneficiaries=beneficiaries; this.logins=logins; this.json=json; this.clock=clock;
        this.events=events;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=15)
    public boolean analyze(UUID transactionId) {
        return perform(transactionId);
    }
    @Transactional(propagation=Propagation.MANDATORY,timeout=15)
    public boolean analyzeInTransaction(UUID transactionId) { return perform(transactionId); }
    private boolean perform(UUID transactionId) {
        var found=transactions.find(transactionId);
        if (found.isEmpty()) return false;
        var fact=found.get(); var now=Timestamp.from(clock.instant()); UUID run=UUID.randomUUID();
        var claimed=jdbc.query("""
            INSERT INTO fraud_analysis_runs(id,transaction_id,analysis_version,score,created_at)
            VALUES (?,?,?,0,?) ON CONFLICT(transaction_id,analysis_version) DO NOTHING RETURNING id
            """,(rs,n)->rs.getObject("id",UUID.class),run,transactionId,ANALYSIS_VERSION,now);
        if (claimed.isEmpty()) return false;
        var snapshot=rules.snapshot();
        var contexts=new EnumMap<FraudRule.Code,FraudContext>(FraudRule.Code.class);
        var result=scores.evaluate(snapshot,rule->{
            long count=rule.code()==FraudRule.Code.VELOCITY?transactions.countOutgoing(fact,rule.threshold().windowSeconds()):0;
            var beneficiary=rule.code()==FraudRule.Code.NEW_BENEFICIARY?beneficiaries.activeSince(fact.customerId(),fact.destinationAccountId(),fact.at()).orElse(null):null;
            long failures=rule.code()==FraudRule.Code.FAILED_LOGIN_CONTEXT?logins.failedBefore(fact.actorId(),fact.at(),rule.threshold().windowSeconds()):0;
            var context=new FraudContext(fact.id(),fact.sourceAccountId(),fact.destinationAccountId(),fact.customerId(),fact.actorId(),fact.amount(),fact.at(),count,beneficiary,failures);
            contexts.put(rule.code(),context); return context;
        });
        jdbc.update("UPDATE fraud_analysis_runs SET score=?,rule_snapshot=cast(? as jsonb),context_snapshot=cast(? as jsonb) WHERE id=?",
            result.score(),json.writeValueAsString(snapshot),json.writeValueAsString(Map.of("eventTime",fact.at(),"sourceAccountId",fact.sourceAccountId(),
                "actorContextAvailable",fact.actorId()!=null,"ruleContexts",contexts)),run);
        if (!result.matches().isEmpty()) {
            UUID alert=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO fraud_alerts(id,transaction_id,analysis_version,score,risk_level,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                """,alert,transactionId,ANALYSIS_VERSION,result.score(),result.level().name(),now,now);
            for (var match:result.matches()) jdbc.update("""
                INSERT INTO fraud_alert_rule_matches(alert_id,rule_code,rule_version,score_contribution,observed_value,threshold_snapshot,reason,created_at)
                VALUES (?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?)
                """,alert,match.ruleCode().name(),match.ruleVersion(),match.scoreContribution(),json.writeValueAsString(match.observedValue()),
                    json.writeValueAsString(match.thresholdSnapshot()),match.reason(),now);
            events.publishEvent(new FraudAlertCreated(alert));
        }
        return true;
    }
}
