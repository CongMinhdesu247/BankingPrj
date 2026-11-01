package com.minh.banking.phase9;

import com.minh.banking.audit.*;
import com.minh.banking.beneficiary.BeneficiaryHistoryService;
import com.minh.banking.fraud.*;
import com.minh.banking.transaction.service.TransactionFraudQueryService;
import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import com.minh.banking.user.enums.RoleCode;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

@TestPropertySource(properties="banking.fraud.recovery-enabled=false")
class FraudWorkflowIT extends TransferIntegrationTestSupport {
    @MockitoBean Clock clock;
    @MockitoSpyBean FraudAnalysisService analysis;
    @Autowired FraudRecoveryJob recovery;
    @Autowired FraudRuleService rules;
    @Autowired TransactionFraudQueryService facts;
    @Autowired BeneficiaryHistoryService beneficiaryHistory;
    @Autowired LoginHistoryQueryService logins;
    @Autowired LoginAuditWriter loginAudit;
    @MockitoSpyBean AuditService audit;
    private static final Instant AT=Instant.parse("2026-10-27T12:00:00Z");
    @BeforeEach void time() { when(clock.instant()).thenReturn(AT); }

    @Test void defaultConfigurationDoesNotSeedRowsAndCannotBeReadByCustomersOrStaff() throws Exception {
        assertThat(count("fraud_rules")).isZero();
        var response=send(admin(),"GET","/fraud-rules",null,null);
        assertThat(response.statusCode()).isEqualTo(200); assertThat(json.readTree(response.body())).hasSize(4);
        for (String token:List.of(testUsers.customer(SOURCE),testUsers.staff(),risk())) {
            assertError(send(token,"GET","/fraud-rules",null,null),403,"FORBIDDEN");
            assertError(send(token,"PATCH","/fraud-rules/"+rules.snapshot().getFirst().id(),"{}",null),403,"FORBIDDEN");
        }
    }
    @Test void transferAutomaticallyAnalyzesAfterCommitAndNoMatchRunIsNotRepeated() throws Exception {
        var response=transfer("no-alert","1"); assertThat(response.statusCode()).isEqualTo(201);
        UUID id=transactionId(response);
        assertThat(count("fraud_analysis_runs")).isEqualTo(1); assertThat(count("fraud_alerts")).isZero();
        assertThat(recovery.recover()).isZero(); assertThat(analysis.analyze(id)).isFalse();
        var snapshot=jdbc.queryForObject("SELECT rule_snapshot::text FROM fraud_analysis_runs WHERE transaction_id=?",String.class,id);
        assertThat(json.readTree(snapshot)).hasSize(4); assertLedgerConsistent(1);
    }
    @Test void lowScoreRuleStillCreatesAlertAndStableEvidenceSurvivesRuleUpdates() throws Exception {
        configure(FraudRule.Code.HIGH_AMOUNT,new FraudRule.Threshold("1000",null,null),20,true);
        var response=transfer("low-risk","1000"); UUID id=alertFor(transactionId(response));
        var before=send(risk(),"GET","/fraud-alerts/"+id,null,null);
        var detail=json.readTree(before.body()); assertThat(detail.get("alert").get("score").asInt()).isEqualTo(20);
        assertThat(detail.get("alert").get("riskLevel").asText()).isEqualTo("LOW");
        configure(FraudRule.Code.HIGH_AMOUNT,new FraudRule.Threshold("99999",null,null),99,false);
        var after=send(risk(),"GET","/fraud-alerts/"+id,null,null);
        assertThat(json.readTree(after.body()).get("matches")).isEqualTo(detail.get("matches"));
        assertThat(json.readTree(after.body()).get("ruleSnapshot")).isEqualTo(detail.get("ruleSnapshot"));
        assertThat(analysis.analyze(transactionId(response))).isFalse();
        assertThat(transfer("new-disabled","1000").statusCode()).isEqualTo(201);
        assertThat(count("fraud_alerts")).isEqualTo(1); assertThat(count("fraud_analysis_runs")).isEqualTo(2);
    }
    @Test void fifthTransferTriggersVelocityAndRecoveryExcludesFutureAndGreaterUuidTies() throws Exception {
        for (int i=0;i<5;i++) { when(clock.instant()).thenReturn(AT.plusSeconds(i)); assertThat(transfer("velocity-"+i,"1").statusCode()).isEqualTo(201); }
        assertThat(count("fraud_alerts")).isEqualTo(1);
        var row=jdbc.queryForMap("SELECT observed_value,rule_code FROM fraud_alert_rule_matches");
        assertThat(row.get("rule_code")).isEqualTo("VELOCITY"); assertThat(json.readTree(row.get("observed_value").toString()).get("count").asInt()).isEqualTo(5);
        // Three more real transfers share event-time. UUID ordering determines the prefix.
        when(clock.instant()).thenReturn(AT.plusSeconds(30));
        for (int i=0;i<3;i++) transfer("tie-"+i,"1");
        var ids=jdbc.queryForList("SELECT id FROM transactions WHERE type='INTERNAL_TRANSFER' AND created_at=? ORDER BY id",UUID.class,Timestamp.from(AT.plusSeconds(30)));
        var middle=facts.find(ids.get(1)).orElseThrow();
        assertThat(facts.countOutgoing(middle,600)).isEqualTo(7); // 5 earlier + 2 ties, no greater tie.
        var first=jdbc.queryForObject("SELECT id FROM transactions WHERE type='INTERNAL_TRANSFER' ORDER BY created_at,id LIMIT 1",UUID.class);
        assertThat(facts.countOutgoing(facts.find(first).orElseThrow(),600)).isEqualTo(1);
    }
    @Test void velocityLowerBoundaryIsExcluded() throws Exception {
        when(clock.instant()).thenReturn(AT.minusSeconds(600)); transfer("expired-window","1");
        when(clock.instant()).thenReturn(AT.minusSeconds(599)); transfer("inside-window","1");
        when(clock.instant()).thenReturn(AT); UUID id=transactionId(transfer("window-target","1"));
        assertThat(facts.countOutgoing(facts.find(id).orElseThrow(),600)).isEqualTo(2);
    }
    @Test void beneficiaryHistoryRespectsCreationDeletionAndExactOneDayBoundary() throws Exception {
        UUID owner=owner(); UUID old=UUID.randomUUID();
        jdbc.update("INSERT INTO beneficiaries(id,owner_customer_id,destination_account_id,created_at,deleted_at) VALUES (?,?,?,?,?)",
            old,owner,DESTINATION,Timestamp.from(AT.minusSeconds(86400)),Timestamp.from(AT.plusSeconds(1)));
        assertThat(beneficiaryHistory.activeSince(owner,DESTINATION,AT)).contains(AT.minusSeconds(86400));
        configure(FraudRule.Code.NEW_BENEFICIARY,new FraudRule.Threshold("1000",null,86400),30,true);
        transfer("old-beneficiary","1000"); assertThat(count("fraud_alerts")).isZero();
        jdbc.update("UPDATE beneficiaries SET deleted_at=? WHERE id=?",Timestamp.from(AT),old);
        jdbc.update("INSERT INTO beneficiaries(owner_customer_id,destination_account_id,created_at) VALUES (?,?,?)",owner,DESTINATION,Timestamp.from(AT.plusSeconds(1)));
        assertThat(beneficiaryHistory.activeSince(owner,DESTINATION,AT)).isEmpty();
        var id=alertFor(transactionId(transfer("future-beneficiary","1000")));
        assertThat(send(risk(),"GET","/fraud-alerts/"+id,null,null).body()).contains("UNSAVED_RECIPIENT_AND_HIGH_AMOUNT");
    }
    @Test void recentlySavedBeneficiaryProducesDistinctReason() throws Exception {
        configure(FraudRule.Code.NEW_BENEFICIARY,new FraudRule.Threshold("1000",null,86400),30,true);
        jdbc.update("INSERT INTO beneficiaries(owner_customer_id,destination_account_id,created_at) VALUES (?,?,?)",owner(),DESTINATION,Timestamp.from(AT.minusSeconds(1)));
        UUID id=alertFor(transactionId(transfer("recent-beneficiary","1000")));
        assertThat(send(risk(),"GET","/fraud-alerts/"+id,null,null).body()).contains("RECENT_BENEFICIARY_AND_HIGH_AMOUNT");
    }
    @Test void realFailedLoginsAreAttributedToActorAndUseEventTimeWindow() throws Exception {
        String token=testUsers.customer(SOURCE),username=testUsers.username(RoleCode.CUSTOMER,owner());
        UUID actor=testUsers.ensure(RoleCode.CUSTOMER,owner());
        when(clock.instant()).thenReturn(AT.minusSeconds(1));
        for (int i=0;i<5;i++) assertError(send(null,"POST","/auth/login",json.writeValueAsString(Map.of("username",username,"password","wrong")),null),401,"INVALID_CREDENTIALS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE user_id=? AND NOT success",Integer.class,actor)).isEqualTo(5);
        when(clock.instant()).thenReturn(AT);
        var response=send(token,"POST","/transfers",payload(SOURCE,DESTINATION,"1"),"login-context");
        assertThat(response.statusCode()).isEqualTo(201); UUID id=alertFor(transactionId(response));
        assertThat(send(risk(),"GET","/fraud-alerts/"+id,null,null).body()).contains("ACTOR_FAILED_LOGINS_BEFORE_TRANSFER");
        assertThat(logins.failedBefore(actor,AT.minusSeconds(2),900)).isZero();
        assertThat(logins.failedBefore(actor,AT.plusSeconds(899),900)).isZero();
        jdbc.update("DELETE FROM idempotency_keys WHERE transaction_id=?",transactionId(response));
        assertThat(facts.find(transactionId(response)).orElseThrow().actorId()).isEqualTo(actor);
    }
    @Test void unknownUserFailuresAndFutureLoginsDoNotCountForDifferentActor() {
        UUID actor=testUsers.ensure(RoleCode.CUSTOMER,owner());
        when(clock.instant()).thenReturn(AT.minusSeconds(1)); loginAudit.record("unknown.person",null,false);
        when(clock.instant()).thenReturn(AT.plusSeconds(1)); loginAudit.record(testUsers.username(RoleCode.CUSTOMER,owner()),null,false);
        assertThat(logins.failedBefore(actor,AT,900)).isZero();
    }
    @Test void concurrentDuplicateAnalysisCreatesOneRunAlertAndMatch() throws Exception {
        configure(FraudRule.Code.HIGH_AMOUNT,new FraudRule.Threshold("1000",null,null),40,true);
        var target=AopTestUtils.<FraudAnalysisService>getUltimateTargetObject(analysis);
        doReturn(false).when(target).analyze(any(UUID.class));
        UUID transaction=transactionId(transfer("duplicate-analysis","1000"));
        doCallRealMethod().when(target).analyze(any(UUID.class));
        var start=new CyclicBarrier(4);
        try (var pool=Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for (int i=0;i<4;i++) tasks.add(pool.submit(()->{ start.await(); return analysis.analyze(transaction); }));
            int winners=0; for (var task:tasks) if (task.get(10,TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(count("fraud_analysis_runs")).isEqualTo(1); assertThat(count("fraud_alerts")).isEqualTo(1);
        assertThat(count("fraud_alert_rule_matches")).isEqualTo(1); assertLedgerConsistent(1);
    }
    @Test void analysisFailureRollsBackAllEvidenceButNotMoneyThenRecoveryFinishes() throws Exception {
        configure(FraudRule.Code.HIGH_AMOUNT,new FraudRule.Threshold("1000",null,null),40,true);
        var target=AopTestUtils.<FraudAnalysisService>getUltimateTargetObject(analysis);
        doAnswer(call->{ call.callRealMethod(); throw new IllegalStateException("Injected after analysis writes before commit"); }).when(target).analyze(any(UUID.class));
        var response=transfer("recoverable","1000"); assertThat(response.statusCode()).isEqualTo(201);
        assertLedgerConsistent(1); var balances=accountSnapshot();
        assertThat(count("fraud_analysis_runs")).isZero(); assertThat(count("fraud_alerts")).isZero(); assertThat(count("fraud_alert_rule_matches")).isZero();
        doCallRealMethod().when(target).analyze(any(UUID.class));
        assertThat(recovery.recover()).isEqualTo(1); assertThat(recovery.recover()).isZero();
        assertThat(count("fraud_alerts")).isEqualTo(1); assertThat(accountSnapshot()).isEqualTo(balances);
        var replay=transfer("recoverable","1000"); assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(count("fraud_analysis_runs")).isEqualTo(1); assertLedgerConsistent(1);
    }
    @Test void assignmentResolutionAndAuditAreAtomicAndDoNotRefund() throws Exception {
        UUID id=createAlert(); var balances=accountSnapshot(); String token=risk(); UUID officer=testUsers.ensure(RoleCode.RISK_OFFICER,null);
        var assigned=send(token,"PATCH","/fraud-alerts/"+id+"/assignment",json.writeValueAsString(Map.of("expectedVersion",0,"assignedTo",officer)),null);
        assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(200);
        assertThat(json.readTree(assigned.body()).get("alert").get("status").asText()).isEqualTo("IN_REVIEW");
        assertError(send(token,"PATCH","/fraud-alerts/"+id+"/resolution","{\"expectedVersion\":1,\"resolution\":\"CONFIRMED_FRAUD\",\"notes\":\" \"}",null),400,"VALIDATION_ERROR");
        var resolved=send(token,"PATCH","/fraud-alerts/"+id+"/resolution","{\"expectedVersion\":1,\"resolution\":\"CONFIRMED_FRAUD\",\"notes\":\" Đã đối chiếu chứng từ mô phỏng \"}",null);
        assertThat(resolved.statusCode()).as(resolved.body()).isEqualTo(200);
        var detail=json.readTree(resolved.body()); assertThat(detail.get("history")).hasSize(2);
        assertThat(detail.get("alert").get("notes").asText()).isEqualTo("Đã đối chiếu chứng từ mô phỏng");
        assertError(send(token,"PATCH","/fraud-alerts/"+id+"/assignment","{\"expectedVersion\":2,\"assignedTo\":null}",null),409,"FRAUD_ALERT_STATE_CONFLICT");
        assertThat(accountSnapshot()).isEqualTo(balances); assertLedgerConsistent(1);
        assertThat(jdbc.queryForList("SELECT details::text FROM audit_logs WHERE target_type='FRAUD_ALERT'").toString()).doesNotContain("chứng từ");
    }
    @Test void staleAssignmentAndTwoConcurrentResolutionsProduceConflict() throws Exception {
        UUID id=createAlert(); String token=risk(); UUID officer=testUsers.ensure(RoleCode.RISK_OFFICER,null);
        String assignment=json.writeValueAsString(Map.of("expectedVersion",0,"assignedTo",officer));
        assertThat(send(token,"PATCH","/fraud-alerts/"+id+"/assignment",assignment,null).statusCode()).isEqualTo(200);
        assertError(send(token,"PATCH","/fraud-alerts/"+id+"/assignment",assignment,null),409,"FRAUD_ALERT_CONFLICT");
        String body="{\"expectedVersion\":1,\"resolution\":\"FALSE_POSITIVE\",\"notes\":\"Hai phiên làm việc đồng thời\"}";
        var start=new CyclicBarrier(2);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{ start.await(); return send(token,"PATCH","/fraud-alerts/"+id+"/resolution",body,null); });
            var second=pool.submit(()->{ start.await(); return send(token,"PATCH","/fraud-alerts/"+id+"/resolution",body,null); });
            assertThat(List.of(first.get().statusCode(),second.get().statusCode())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action='FRAUD_ALERT_RESOLVED'",Integer.class)).isEqualTo(1);
    }
    @Test void disabledOrNonRiskAssigneeIsRejectedAndOtherOfficerCannotResolve() throws Exception {
        UUID id=createAlert(); String token=risk(); UUID staff=testUsers.ensure(RoleCode.BANK_STAFF,null);
        assertError(send(token,"PATCH","/fraud-alerts/"+id+"/assignment",json.writeValueAsString(Map.of("expectedVersion",0,"assignedTo",staff)),null),400,"INVALID_ASSIGNEE");
        UUID second=UUID.randomUUID(); UUID original=testUsers.ensure(RoleCode.RISK_OFFICER,null);
        jdbc.update("INSERT INTO users(id,username,password_hash,enabled) SELECT ?,?,password_hash,false FROM users WHERE id=?",second,"risk.second",original);
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,role_id FROM user_roles WHERE user_id=?",second,original);
        assertError(send(token,"PATCH","/fraud-alerts/"+id+"/assignment",json.writeValueAsString(Map.of("expectedVersion",0,"assignedTo",second)),null),400,"INVALID_ASSIGNEE");
        jdbc.update("UPDATE users SET enabled=true WHERE id=?",second);
        assertThat(send(token,"PATCH","/fraud-alerts/"+id+"/assignment",json.writeValueAsString(Map.of("expectedVersion",0,"assignedTo",second)),null).statusCode()).isEqualTo(200);
        assertError(send(token,"PATCH","/fraud-alerts/"+id+"/resolution","{\"expectedVersion\":1,\"resolution\":\"FALSE_POSITIVE\",\"notes\":\"Not my assignment\"}",null),409,"FRAUD_ALERT_STATE_CONFLICT");
    }
    @Test void twoOfficersAssigningSameVersionCannotOverwriteEachOther() throws Exception {
        UUID id=createAlert(); String first=risk(); UUID firstId=testUsers.ensure(RoleCode.RISK_OFFICER,null);
        String password="Second-Risk-Demo-Password-123!";
        var created=send(admin(),"POST","/users",json.writeValueAsString(Map.of("username","risk.concurrent","password",password,"roles",List.of("RISK_OFFICER"))),null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID secondId=UUID.fromString(json.readTree(created.body()).get("id").asText());
        String second="Bearer "+json.readTree(send(null,"POST","/auth/login",json.writeValueAsString(Map.of("username","risk.concurrent","password",password)),null).body()).get("accessToken").asText();
        var start=new CyclicBarrier(2);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{ start.await(); return send(first,"PATCH","/fraud-alerts/"+id+"/assignment",json.writeValueAsString(Map.of("expectedVersion",0,"assignedTo",firstId)),null); });
            var b=pool.submit(()->{ start.await(); return send(second,"PATCH","/fraud-alerts/"+id+"/assignment",json.writeValueAsString(Map.of("expectedVersion",0,"assignedTo",secondId)),null); });
            assertThat(List.of(a.get().statusCode(),b.get().statusCode())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action='FRAUD_ALERT_ASSIGNED'",Integer.class)).isEqualTo(1);
    }
    @Test void auditFailureRollsBackAssignmentAndRuleUpdate() throws Exception {
        UUID id=createAlert(); String token=risk(); UUID officer=testUsers.ensure(RoleCode.RISK_OFFICER,null);
        var target=AopTestUtils.<AuditService>getUltimateTargetObject(audit);
        doAnswer(call->{ call.callRealMethod(); throw new IllegalStateException("Audit fault"); }).when(target).success(eq(AuditService.Action.FRAUD_ALERT_ASSIGNED),anyString(),any(UUID.class),anyMap());
        assertError(send(token,"PATCH","/fraud-alerts/"+id+"/assignment",json.writeValueAsString(Map.of("expectedVersion",0,"assignedTo",officer)),null),500,"INTERNAL_ERROR");
        assertThat(jdbc.queryForObject("SELECT version FROM fraud_alerts WHERE id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action='FRAUD_ALERT_ASSIGNED'",Integer.class)).isZero();
        doAnswer(call->{ call.callRealMethod(); throw new IllegalStateException("Audit fault"); }).when(target).success(eq(AuditService.Action.FRAUD_RULE_UPDATED),anyString(),any(UUID.class),anyMap());
        var rule=rules.snapshot().getFirst(); int version=rule.version();
        var result=send(admin(),"PATCH","/fraud-rules/"+rule.id(),json.writeValueAsString(new FraudRuleService.Update(version,rule.threshold(),50,true)),null);
        assertError(result,500,"INTERNAL_ERROR"); assertThat(rules.snapshot().getFirst().version()).isEqualTo(version);
    }
    @Test void ruleValidationAndStaleVersionAreControlled() throws Exception {
        var rule=rules.snapshot().getFirst(); String token=admin();
        var request=new FraudRuleService.Update(rule.version(),new FraudRule.Threshold("1000",null,null),40,true);
        assertThat(send(token,"PATCH","/fraud-rules/"+rule.id(),json.writeValueAsString(request),null).statusCode()).isEqualTo(200);
        assertError(send(token,"PATCH","/fraud-rules/"+rule.id(),json.writeValueAsString(request),null),409,"FRAUD_RULE_CONFLICT");
        assertError(send(token,"PATCH","/fraud-rules/"+rule.id(),"{\"expectedVersion\":2,\"threshold\":{\"amount\":\"1.5\",\"count\":null,\"windowSeconds\":null},\"weight\":40,\"enabled\":true}",null),400,"INVALID_FRAUD_RULE");
        assertError(send(token,"PATCH","/fraud-rules/"+rule.id(),"{\"expectedVersion\":2,\"threshold\":{},\"weight\":101,\"enabled\":true}",null),400,"VALIDATION_ERROR");
        assertError(send(token,"PATCH","/fraud-rules/"+rule.id(),"{\"expectedVersion\":2,\"threshold\":{\"amount\":1000},\"weight\":40,\"enabled\":true}",null),400,"INVALID_REQUEST");
        assertError(send(token,"PATCH","/fraud-rules/"+rule.id(),"{\"expectedVersion\":2.5,\"threshold\":{\"amount\":\"1000\"},\"weight\":40,\"enabled\":true}",null),400,"INVALID_REQUEST");
    }
    @Test void fraudReadFiltersPaginationSummaryAndRoleBoundaries() throws Exception {
        UUID id=createAlert(); String token=risk();
        var list=send(token,"GET","/fraud-alerts?riskLevel=MEDIUM&status=OPEN&size=1",null,null);
        assertThat(json.readTree(list.body()).get("totalElements").asInt()).isEqualTo(1);
        assertThat(json.readTree(send(token,"GET","/fraud-alerts?riskLevel=CRITICAL",null,null).body()).get("totalElements").asInt()).isZero();
        var summary=json.readTree(send(token,"GET","/fraud/summary",null,null).body());
        assertThat(summary.get("open").asInt()).isEqualTo(1); assertThat(summary.get("analyzedTransfers").asInt()).isEqualTo(1);
        assertThat(summary.get("pendingTransfers").asInt()).isZero();
        for (String denied:List.of(testUsers.customer(SOURCE),testUsers.staff(),admin())) {
            for (String path:List.of("/fraud/summary","/fraud-alerts","/fraud-alerts/"+id,"/fraud/officers")) assertError(send(denied,"GET",path,null,null),403,"FORBIDDEN");
        }
        assertError(send(token,"GET","/fraud-alerts?status=INVALID",null,null),400,"INVALID_REQUEST");
        assertError(send(token,"GET","/fraud-alerts/"+UUID.randomUUID(),null,null),404,"FRAUD_ALERT_NOT_FOUND");
        assertError(send(null,"GET","/fraud/summary",null,null),401,"UNAUTHENTICATED");
    }
    @Test void staffInvestigationLookupAllowsRiskButNeverCustomer() throws Exception {
        var customer=send(risk(),"GET","/staff/customers?search=TRANSFER_FIXTURE",null,null);
        assertThat(json.readTree(customer.body()).get("totalElements").asInt()).isEqualTo(1);
        assertThat(send(risk(),"GET","/staff/customers/"+owner(),null,null).statusCode()).isEqualTo(200);
        assertThat(json.readTree(send(risk(),"GET","/staff/customers/"+owner()+"/accounts",null,null).body()).get("totalElements").asInt()).isEqualTo(2);
        assertError(send(testUsers.customer(SOURCE),"GET","/staff/customers",null,null),403,"FORBIDDEN");
        assertError(send(risk(),"PATCH","/customers/"+owner(),"{\"email\":\"no@example.com\"}",null),403,"FORBIDDEN");
    }

    private String admin() { return testUsers.bearer(RoleCode.ADMIN,null); }
    private String risk() { return testUsers.bearer(RoleCode.RISK_OFFICER,null); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    private UUID owner() { return jdbc.queryForObject("SELECT customer_id FROM accounts WHERE id=?",UUID.class,SOURCE); }
    private UUID transactionId(HttpResponse<String> response) { assertThat(response.statusCode()).as(response.body()).isEqualTo(201); return UUID.fromString(json.readTree(response.body()).get("transaction").get("id").asText()); }
    private UUID alertFor(UUID transaction) { return jdbc.queryForObject("SELECT id FROM fraud_alerts WHERE transaction_id=?",UUID.class,transaction); }
    private UUID createAlert() throws Exception { configure(FraudRule.Code.HIGH_AMOUNT,new FraudRule.Threshold("1000",null,null),40,true); return alertFor(transactionId(transfer("alert-fixture","1000"))); }
    private void configure(FraudRule.Code code,FraudRule.Threshold threshold,int weight,boolean enabled) throws Exception {
        var rule=rules.snapshot().stream().filter(r->r.code()==code).findFirst().orElseThrow();
        var response=send(admin(),"PATCH","/fraud-rules/"+rule.id(),json.writeValueAsString(new FraudRuleService.Update(rule.version(),threshold,weight,enabled)),null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }
    private HttpResponse<String> transfer(String key,String amount) throws Exception { return send(testUsers.customer(SOURCE),"POST","/transfers",payload(SOURCE,DESTINATION,amount),key); }
    private HttpResponse<String> send(String token,String method,String path,String body,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(12)).header("Content-Type","application/json").header("X-Request-Id","transfer-it");
        if (token!=null) builder.header("Authorization",token); if (key!=null) builder.header("Idempotency-Key",key);
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
