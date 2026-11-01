package com.minh.banking.fraud;

import com.minh.banking.audit.AuditService;
import com.minh.banking.common.exception.*;
import com.minh.banking.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service @Transactional(readOnly=true)
public class FraudRuleService {
    public record Update(@NotNull @Min(1) Integer expectedVersion,@NotNull @Valid FraudRule.Threshold threshold,
        @NotNull @Min(0) @Max(100) Integer weight,@NotNull Boolean enabled) {}
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final CurrentUser current;
    private final AuditService audit;
    public FraudRuleService(JdbcTemplate jdbc,JsonMapper json,Clock clock,CurrentUser current,AuditService audit) {
        this.jdbc=jdbc; this.json=json; this.clock=clock; this.current=current; this.audit=audit;
    }
    /** Internal immutable snapshot, available to post-commit/recovery without a user session. */
    public List<FraudRule> snapshot() {
        var rules=new EnumMap<FraudRule.Code,FraudRule>(FraudRule.Code.class);
        FraudRule.defaults().forEach(rule->rules.put(rule.code(),rule));
        jdbc.query("SELECT * FROM fraud_rules ORDER BY code",(rs,n)->new FraudRule(rs.getObject("id",UUID.class),
            FraudRule.Code.valueOf(rs.getString("code")),rs.getInt("version"),json.readValue(rs.getString("threshold"),FraudRule.Threshold.class),
            rs.getInt("weight"),rs.getBoolean("enabled"),rs.getObject("updated_by",UUID.class),rs.getTimestamp("updated_at").toInstant()))
            .forEach(rule->{ validate(rule.code(),rule.threshold()); rules.put(rule.code(),rule); });
        return List.copyOf(rules.values());
    }
    @PreAuthorize("hasRole('ADMIN')")
    public List<FraudRule> list() { return snapshot(); }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public FraudRule update(UUID id,Update request) {
        var original=snapshot().stream().filter(rule->rule.id().equals(id)).findFirst().orElseThrow(()->
            new BusinessException(ErrorCode.FRAUD_RULE_NOT_FOUND,"Fraud rule was not found."));
        validate(original.code(),request.threshold());
        if (original.version()!=request.expectedVersion()) throw conflict();
        var now=clock.instant(); int next=Math.addExact(original.version(),1);
        var result=jdbc.query("""
            INSERT INTO fraud_rules(id,code,version,threshold,weight,enabled,updated_by,created_at,updated_at)
            VALUES (?,?,?,cast(? as jsonb),?,?,?,?,?)
            ON CONFLICT(code) DO UPDATE SET version=EXCLUDED.version,threshold=EXCLUDED.threshold,
              weight=EXCLUDED.weight,enabled=EXCLUDED.enabled,updated_by=EXCLUDED.updated_by,updated_at=EXCLUDED.updated_at
            WHERE fraud_rules.version=? RETURNING id
            """,(rs,n)->rs.getObject("id",UUID.class),id,original.code().name(),next,json.writeValueAsString(request.threshold()),
                request.weight(),request.enabled(),current.get().id(),Timestamp.from(now),Timestamp.from(now),request.expectedVersion());
        if (result.isEmpty()) throw conflict();
        audit.success(AuditService.Action.FRAUD_RULE_UPDATED,"FRAUD_RULE",id,Map.of("code",original.code(),"previousVersion",original.version(),"version",next));
        return new FraudRule(id,original.code(),next,request.threshold(),request.weight(),request.enabled(),current.get().id(),now);
    }
    public static void validate(FraudRule.Code code,FraudRule.Threshold threshold) {
        boolean amount=code==FraudRule.Code.HIGH_AMOUNT || code==FraudRule.Code.NEW_BENEFICIARY;
        boolean count=code==FraudRule.Code.VELOCITY || code==FraudRule.Code.FAILED_LOGIN_CONTEXT;
        boolean window=code!=FraudRule.Code.HIGH_AMOUNT;
        boolean valid=threshold!=null && (amount?threshold.amount()!=null && threshold.amount().matches("[0-9]{1,17}")
            && new java.math.BigDecimal(threshold.amount()).signum()>0:threshold.amount()==null)
            && (count?threshold.count()!=null && threshold.count()>=1 && threshold.count()<=100000:threshold.count()==null)
            && (window?threshold.windowSeconds()!=null && threshold.windowSeconds()>=1 && threshold.windowSeconds()<=604800:threshold.windowSeconds()==null);
        if (!valid) throw new BusinessException(ErrorCode.INVALID_FRAUD_RULE,"Threshold fields or values are invalid for this rule.");
    }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.FRAUD_RULE_CONFLICT,"Rule configuration changed. Reload before updating."); }
}
