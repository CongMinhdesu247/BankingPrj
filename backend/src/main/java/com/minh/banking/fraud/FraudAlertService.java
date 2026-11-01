package com.minh.banking.fraud;

import com.minh.banking.audit.AuditService;
import com.minh.banking.common.exception.*;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.security.CurrentUser;
import jakarta.validation.constraints.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service @Transactional(readOnly=true) @PreAuthorize("hasRole('RISK_OFFICER')")
public class FraudAlertService {
    public record Assignment(@NotNull @PositiveOrZero Long expectedVersion,UUID assignedTo) {}
    public record Resolution(@NotNull @PositiveOrZero Long expectedVersion,@NotNull FraudAlert.Resolution resolution,
        @NotBlank @Size(max=2000) String notes) {}
    public record Change(String action,UUID actorId,JsonNode details,Instant createdAt) {}
    public record Detail(FraudAlert alert,List<FraudAlertRuleMatch> matches,JsonNode ruleSnapshot,JsonNode contextSnapshot,List<Change> history) {}
    public record Officer(UUID id,String username) {}
    public record Summary(long totalAlerts,long open,long inReview,long confirmedFraud,long falsePositive,long highPriorityOpen,
        long analyzedTransfers,long pendingTransfers,Map<String,Long> byRiskLevel,Instant lastAnalyzedAt) {}
    private static final String SELECT="""
        SELECT f.*,t.reference,t.amount::text AS amount,t.currency,t.created_at AS transaction_at,
        t.source_account_id,t.destination_account_id,u.username AS assigned_username FROM fraud_alerts f
        JOIN transactions t ON t.id=f.transaction_id LEFT JOIN users u ON u.id=f.assigned_to
        """;
    private static final RowMapper<FraudAlert> ROW=(rs,n)->new FraudAlert(rs.getObject("id",UUID.class),rs.getObject("transaction_id",UUID.class),
        rs.getString("reference"),rs.getString("amount"),rs.getString("currency"),rs.getTimestamp("transaction_at").toInstant(),
        rs.getObject("source_account_id",UUID.class),rs.getObject("destination_account_id",UUID.class),rs.getInt("analysis_version"),
        rs.getInt("score"),RiskLevel.valueOf(rs.getString("risk_level")),FraudAlert.Status.valueOf(rs.getString("status")),
        rs.getObject("assigned_to",UUID.class),rs.getString("assigned_username"),rs.getString("notes"),instant(rs,"resolved_at"),
        rs.getLong("version"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final JsonMapper json;
    private final Clock clock;
    private final CurrentUser current;
    private final AuditService audit;
    public FraudAlertService(JdbcTemplate jdbc,NamedParameterJdbcTemplate named,JsonMapper json,Clock clock,CurrentUser current,AuditService audit) {
        this.jdbc=jdbc; this.named=named; this.json=json; this.clock=clock; this.current=current; this.audit=audit;
    }
    public PageResponse<FraudAlert> list(int page,int size,FraudAlert.Status status,RiskLevel riskLevel,UUID transactionId) {
        var params=new MapSqlParameterSource(); String where=" WHERE 1=1";
        if (status!=null) { where+=" AND f.status=:status"; params.addValue("status",status.name()); }
        if (riskLevel!=null) { where+=" AND f.risk_level=:level"; params.addValue("level",riskLevel.name()); }
        if (transactionId!=null) { where+=" AND f.transaction_id=:tx"; params.addValue("tx",transactionId); }
        long total=named.queryForObject("SELECT count(*) FROM fraud_alerts f"+where,params,Long.class);
        params.addValue("size",size).addValue("offset",(long)page*size);
        var rows=named.query(SELECT+where+" ORDER BY f.created_at DESC,f.id DESC LIMIT :size OFFSET :offset",params,ROW);
        return PageResponse.from(new PageImpl<>(rows,PageRequest.of(page,size),total));
    }
    public Detail detail(UUID id) {
        var alert=find(id);
        var matches=jdbc.query("SELECT * FROM fraud_alert_rule_matches WHERE alert_id=? ORDER BY rule_code,rule_version",(rs,n)->
            new FraudAlertRuleMatch(rs.getObject("id",UUID.class),FraudRule.Code.valueOf(rs.getString("rule_code")),rs.getInt("rule_version"),
                rs.getInt("score_contribution"),json.readTree(rs.getString("observed_value")),json.readTree(rs.getString("threshold_snapshot")),rs.getString("reason")),id);
        var run=jdbc.queryForObject("SELECT rule_snapshot,context_snapshot FROM fraud_analysis_runs WHERE transaction_id=? AND analysis_version=?",
            (rs,n)->List.of(json.readTree(rs.getString("rule_snapshot")),json.readTree(rs.getString("context_snapshot"))),alert.transactionId(),alert.analysisVersion());
        var history=jdbc.query("""
            SELECT action,actor_id,details,created_at FROM audit_logs WHERE target_type='FRAUD_ALERT' AND target_id=?
            ORDER BY created_at,id
            """,(rs,n)->new Change(rs.getString("action"),rs.getObject("actor_id",UUID.class),json.readTree(rs.getString("details")),rs.getTimestamp("created_at").toInstant()),id.toString());
        return new Detail(alert,matches,run.get(0),run.get(1),history);
    }
    public List<Officer> officers() {
        return jdbc.query("""
            SELECT u.id,u.username FROM users u WHERE u.enabled AND EXISTS
            (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.code='RISK_OFFICER')
            ORDER BY u.username,u.id
            """,(rs,n)->new Officer(rs.getObject("id",UUID.class),rs.getString("username")));
    }
    public Summary summary() {
        var levels=new LinkedHashMap<String,Long>(); for (var level:RiskLevel.values()) levels.put(level.name(),0L);
        jdbc.query("SELECT risk_level,count(*) AS n FROM fraud_alerts GROUP BY risk_level",(rs,n)->{
            levels.put(rs.getString("risk_level"),rs.getLong("n")); return 0;
        });
        return jdbc.queryForObject("""
            SELECT count(*) AS total,count(*) FILTER(WHERE status='OPEN') AS open,
            count(*) FILTER(WHERE status='IN_REVIEW') AS review,count(*) FILTER(WHERE status='CONFIRMED_FRAUD') AS confirmed,
            count(*) FILTER(WHERE status='FALSE_POSITIVE') AS false_positive,
            count(*) FILTER(WHERE status IN ('OPEN','IN_REVIEW') AND risk_level IN ('HIGH','CRITICAL')) AS priority,
            (SELECT count(*) FROM fraud_analysis_runs WHERE analysis_version=1) AS analyzed,
            (SELECT max(created_at) FROM fraud_analysis_runs WHERE analysis_version=1) AS last_analyzed,
            (SELECT count(*) FROM transactions t WHERE t.type='INTERNAL_TRANSFER' AND t.status='COMPLETED' AND NOT EXISTS
              (SELECT 1 FROM fraud_analysis_runs r WHERE r.transaction_id=t.id AND r.analysis_version=1)) AS pending
            FROM fraud_alerts
            """,(rs,n)->new Summary(rs.getLong("total"),rs.getLong("open"),rs.getLong("review"),rs.getLong("confirmed"),rs.getLong("false_positive"),
                rs.getLong("priority"),rs.getLong("analyzed"),rs.getLong("pending"),Map.copyOf(levels),instant(rs,"last_analyzed")));
    }
    @Transactional
    public Detail assign(UUID id,Assignment request) {
        var alert=find(id); requireMutable(alert,request.expectedVersion());
        if (request.assignedTo()!=null && officers().stream().noneMatch(officer->officer.id().equals(request.assignedTo())))
            throw new BusinessException(ErrorCode.INVALID_ASSIGNEE,"Assignee must be an enabled risk officer.");
        String status=request.assignedTo()==null?"OPEN":"IN_REVIEW";
        int changed=jdbc.update("""
            UPDATE fraud_alerts SET assigned_to=?,status=?,version=version+1,updated_at=?
            WHERE id=? AND version=? AND status IN ('OPEN','IN_REVIEW')
            """,request.assignedTo(),status,Timestamp.from(clock.instant()),id,request.expectedVersion());
        if (changed!=1) throw conflict();
        audit.success(AuditService.Action.FRAUD_ALERT_ASSIGNED,"FRAUD_ALERT",id,
            Map.of("assignedTo",request.assignedTo()==null?"UNASSIGNED":request.assignedTo().toString(),"status",status,"version",alert.version()+1));
        return detail(id);
    }
    @Transactional
    public Detail resolve(UUID id,Resolution request) {
        var alert=find(id); requireMutable(alert,request.expectedVersion());
        if (alert.status()!=FraudAlert.Status.IN_REVIEW || !current.get().id().equals(alert.assignedTo()))
            throw new BusinessException(ErrorCode.FRAUD_ALERT_STATE_CONFLICT,"Only the assigned officer may resolve an alert in review.");
        var now=Timestamp.from(clock.instant());
        int changed=jdbc.update("""
            UPDATE fraud_alerts SET status=?,notes=?,resolved_at=?,updated_at=?,version=version+1
            WHERE id=? AND version=? AND status='IN_REVIEW' AND assigned_to=?
            """,request.resolution().name(),request.notes().strip(),now,now,id,request.expectedVersion(),current.get().id());
        if (changed!=1) throw conflict();
        audit.success(AuditService.Action.FRAUD_ALERT_RESOLVED,"FRAUD_ALERT",id,
            Map.of("resolution",request.resolution(),"notesRecorded",true,"version",alert.version()+1));
        return detail(id);
    }
    private FraudAlert find(UUID id) {
        return jdbc.query(SELECT+" WHERE f.id=?",ROW,id).stream().findFirst().orElseThrow(()->
            new BusinessException(ErrorCode.FRAUD_ALERT_NOT_FOUND,"Fraud alert was not found."));
    }
    private static void requireMutable(FraudAlert alert,long expected) {
        if (alert.version()!=expected) throw conflict();
        if (alert.status()==FraudAlert.Status.CONFIRMED_FRAUD || alert.status()==FraudAlert.Status.FALSE_POSITIVE)
            throw new BusinessException(ErrorCode.FRAUD_ALERT_STATE_CONFLICT,"Resolved alerts cannot be modified.");
    }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.FRAUD_ALERT_CONFLICT,"Alert changed. Reload before updating."); }
    private static Instant instant(ResultSet rs,String name) throws SQLException { var time=rs.getTimestamp(name); return time==null?null:time.toInstant(); }
}
