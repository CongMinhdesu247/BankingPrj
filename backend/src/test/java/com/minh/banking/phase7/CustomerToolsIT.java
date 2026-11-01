package com.minh.banking.phase7;

import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import com.minh.banking.user.enums.RoleCode;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class CustomerToolsIT extends TransferIntegrationTestSupport {
    private HttpResponse<String> as(String token,String method,String path,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path))
            .header("Content-Type","application/json").header("X-Request-Id","transfer-it");
        if (token!=null) builder.header("Authorization",token);
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void statementUsesWholeMonthlyTotalsAndHidesOtherOwners() throws Exception {
        var month=YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        var token=testUsers.customer(SOURCE);
        var response=as(token,"GET","/me/statement?accountId="+SOURCE+"&month="+month+"&size=1",null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var data=json.readTree(response.body());
        assertThat(new java.math.BigDecimal(data.get("openingBalance").asText())).isZero();
        assertThat(new java.math.BigDecimal(data.get("incoming").asText())).isEqualByComparingTo("1000000");
        assertThat(new java.math.BigDecimal(data.get("outgoing").asText())).isZero();
        assertThat(new java.math.BigDecimal(data.get("closingBalance").asText())).isEqualByComparingTo("1000000");
        assertThat(data.get("entries").get("content").get(0).get("direction").asText()).isEqualTo("CREDIT");
        var next=as(token,"GET","/me/statement?accountId="+SOURCE+"&month="+month.plusMonths(1),null);
        var empty=json.readTree(next.body());
        assertThat(new java.math.BigDecimal(empty.get("openingBalance").asText())).isEqualByComparingTo("1000000");
        assertThat(new java.math.BigDecimal(empty.get("closingBalance").asText())).isEqualByComparingTo("1000000");
        assertThat(empty.get("entries").get("totalElements").asInt()).isZero();
        assertError(as(token,"GET","/me/statement?accountId="+TREASURY+"&month="+month,null),404,"ACCOUNT_NOT_FOUND");
        assertError(as(token,"GET","/me/statement?accountId="+SOURCE+"&month=2026-13",null),400,"INVALID_REQUEST");
        assertError(as(testUsers.staff(),"GET","/me/statement?accountId="+SOURCE+"&month="+month,null),403,"FORBIDDEN");
    }
    @Test void selfContactCannotSelectAnotherCustomerAndInvalidFieldsDoNotPersist() throws Exception {
        var token=testUsers.customer(SOURCE);
        var owner=jdbc.queryForObject("SELECT customer_id FROM accounts WHERE id=?",UUID.class,SOURCE);
        var treasuryOwner=jdbc.queryForObject("SELECT customer_id FROM accounts WHERE id=?",UUID.class,TREASURY);
        var response=as(token,"PATCH","/me/contact","{\"email\":\"self@example.test\",\"phone\":\"0901234567\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id=?",String.class,owner)).isEqualTo("self@example.test");
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id=?",String.class,treasuryOwner)).isNull();
        var me=as(token,"GET","/me",null);
        assertThat(json.readTree(me.body()).get("customer").get("email").asText()).isEqualTo("self@example.test");
        assertError(as(token,"PATCH","/me/contact","{\"email\":\"broken\",\"phone\":\"abc\"}"),400,"VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id=?",String.class,owner)).isEqualTo("self@example.test");
        assertError(as(testUsers.staff(),"PATCH","/me/contact","{\"email\":\"staff@example.test\"}"),403,"FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action='CUSTOMER_UPDATED' AND actor_id IS NOT NULL",Long.class)).isEqualTo(1);
    }
    @Test void loginHistoryNeverLeaksAnotherUsersEventsOrFingerprints() throws Exception {
        var token=testUsers.customer(SOURCE);
        var owner=jdbc.queryForObject("SELECT customer_id FROM accounts WHERE id=?",UUID.class,SOURCE);
        var user=testUsers.ensure(RoleCode.CUSTOMER,owner);
        var other=testUsers.ensure(RoleCode.ADMIN,null);
        jdbc.update("INSERT INTO login_history(user_id,username_fingerprint,success) VALUES (?,'private-self',true),(?,'private-other',true)",user,other);
        var response=as(token,"GET","/me/login-history",null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("totalElements").asInt()).isEqualTo(1);
        assertThat(response.body()).doesNotContain("fingerprint","private-self","private-other",other.toString());
        assertError(as(null,"GET","/me/login-history",null),401,"UNAUTHENTICATED");
    }
    @Test void statementUsesVietnamMonthBoundariesAndTotalsIndependentOfPage() throws Exception {
        var timestamps=List.of("2020-05-31T16:59:59Z","2020-05-31T17:00:00Z","2020-06-30T17:00:00Z");
        int balance=0;
        for (int i=0;i<timestamps.size();i++) {
            var id=UUID.randomUUID(); int amount=(i+1)*10; balance+=amount;
            var at=java.sql.Timestamp.from(Instant.parse(timestamps.get(i)));
            jdbc.update("INSERT INTO transactions(id,reference,source_account_id,destination_account_id,amount,type,created_at) VALUES (?,?,?,?,?,'OPENING',?)",id,id.toString(),TREASURY,SOURCE,amount,at);
            jdbc.update("INSERT INTO transaction_entries(transaction_id,account_id,direction,amount,balance_after,created_at) VALUES (?,?,'CREDIT',?,?,?)",id,SOURCE,amount,balance,at);
        }
        jdbc.update("UPDATE accounts SET balance=balance+60 WHERE id=?",SOURCE);
        var response=as(testUsers.customer(SOURCE),"GET","/me/statement?accountId="+SOURCE+"&month=2020-06&size=1&page=1",null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var data=json.readTree(response.body());
        assertThat(new java.math.BigDecimal(data.get("openingBalance").asText())).isEqualByComparingTo("10");
        assertThat(new java.math.BigDecimal(data.get("incoming").asText())).isEqualByComparingTo("20");
        assertThat(new java.math.BigDecimal(data.get("closingBalance").asText())).isEqualByComparingTo("30");
        assertThat(data.get("entries").get("totalElements").asInt()).isEqualTo(1);
        assertThat(data.get("entries").get("content").size()).isZero();
    }
    @Test void accountRiskUsesOutgoingActivityAndExcludesFalsePositivesAndOldTransfers() throws Exception {
        var high=seedRisk(80,"OPEN",0);
        seedRisk(90,"FALSE_POSITIVE",0);
        seedRisk(100,"OPEN",31);
        var admin=testUsers.bearer(RoleCode.ADMIN,null);
        var response=as(admin,"GET","/staff/account-risk?accountId="+SOURCE,null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var data=json.readTree(response.body()).get("content").get(0);
        assertThat(data.get("riskLevel").asText()).isEqualTo("CRITICAL");
        assertThat(data.get("score").asInt()).isEqualTo(80);
        assertThat(data.get("openAlerts").asInt()).isEqualTo(1);
        assertThat(data.get("analyzed").asInt()).isEqualTo(2);
        var recipient=as(admin,"GET","/staff/account-risk?accountId="+DESTINATION,null);
        assertThat(json.readTree(recipient.body()).get("content").get(0).get("riskLevel").asText()).isEqualTo("UNASSESSED");
        jdbc.update("UPDATE fraud_alerts SET status='FALSE_POSITIVE',notes='Reviewed',resolved_at=now() WHERE transaction_id=?",high);
        var reviewed=as(admin,"GET","/staff/account-risk?accountId="+SOURCE,null);
        assertThat(json.readTree(reviewed.body()).get("content").get(0).get("riskLevel").asText()).isEqualTo("LOW");
        var filtered=as(admin,"GET","/staff/account-risk?riskLevel=CRITICAL",null);
        assertThat(json.readTree(filtered.body()).get("totalElements").asInt()).isZero();
        assertError(as(testUsers.customer(SOURCE),"GET","/staff/account-risk",null),403,"FORBIDDEN");
        assertError(as(testUsers.staff(),"GET","/staff/account-risk",null),403,"FORBIDDEN");
    }
    private UUID seedRisk(int score,String status,int days) {
        UUID tx=UUID.randomUUID();
        var at=java.sql.Timestamp.from(Instant.now().minusSeconds(days*86400L));
        jdbc.update("INSERT INTO transactions(id,reference,source_account_id,destination_account_id,amount,created_at) VALUES (?,?,?,?,100,?)",tx,tx.toString(),SOURCE,DESTINATION,at);
        jdbc.update("INSERT INTO fraud_analysis_runs(transaction_id,analysis_version,score) VALUES (?,1,?)",tx,score);
        jdbc.update("INSERT INTO fraud_alerts(transaction_id,analysis_version,score,risk_level,status,notes,resolved_at) VALUES (?,1,?,'CRITICAL',?,'Fixture',?)",tx,score,status,status.equals("FALSE_POSITIVE")?at:null);
        return tx;
    }
}
