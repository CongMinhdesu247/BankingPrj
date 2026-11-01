package com.minh.banking.phase6;

import com.minh.banking.user.enums.RoleCode;
import java.net.http.HttpResponse;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class BeneficiaryAuditIT extends PhaseSixSupport {
    private String beneficiary() { return "{\"destinationAccountId\":\""+TREASURY+"\",\"nickname\":\"My contact\"}"; }
    @Test void beneficiaryCrudPreservesHistoryAndRecreationHasFreshIdentity() throws Exception {
        String token=testUsers.customer(SOURCE);
        var created=as(token,"POST","/me/beneficiaries",beneficiary(),null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var original=json.readTree(created.body()); String id=original.get("id").asText();
        assertThat(original.get("createdAt").asText()).isNotBlank();
        assertThat(created.body()).doesNotContain("balance","customerId");
        assertError(as(token,"POST","/me/beneficiaries",beneficiary(),null),409,"BENEFICIARY_CONFLICT");
        var updated=as(token,"PATCH","/me/beneficiaries/"+id,"{\"nickname\":\"Updated contact\"}",null);
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(json.readTree(updated.body()).get("createdAt")).isEqualTo(original.get("createdAt"));
        var listed=as(token,"GET","/me/beneficiaries",null,null);
        assertThat(json.readTree(listed.body()).get("totalElements").asInt()).isEqualTo(1);
        assertThat(as(token,"DELETE","/me/beneficiaries/"+id,null,null).statusCode()).isEqualTo(204);
        assertError(as(token,"GET","/me/beneficiaries/"+id,null,null),404,"BENEFICIARY_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT deleted_at>=created_at FROM beneficiaries WHERE id=?",Boolean.class,UUID.fromString(id))).isTrue();
        var recreated=as(token,"POST","/me/beneficiaries",beneficiary(),null);
        assertThat(recreated.statusCode()).as(recreated.body()).isEqualTo(201);
        assertThat(json.readTree(recreated.body()).get("id")).isNotEqualTo(original.get("id"));
        assertThat(count("beneficiaries")).isEqualTo(2);
        assertThat(actions("BENEFICIARY_CREATED")).isEqualTo(2);
        assertThat(actions("BENEFICIARY_UPDATED")).isEqualTo(1);
        assertThat(actions("BENEFICIARY_DELETED")).isEqualTo(1);
    }
    @Test void beneficiaryOwnershipIsEnforcedForReadUpdateDeleteAndList() throws Exception {
        String owner=testUsers.customer(SOURCE),other=testUsers.customer(TREASURY);
        var created=as(owner,"POST","/me/beneficiaries",beneficiary(),null);
        String id=json.readTree(created.body()).get("id").asText();
        for (String method:List.of("GET","PATCH","DELETE"))
            assertError(as(other,method,"/me/beneficiaries/"+id,method.equals("PATCH")?"{\"nickname\":\"Forbidden\"}":null,null),404,"BENEFICIARY_NOT_FOUND");
        assertThat(json.readTree(as(other,"GET","/me/beneficiaries",null,null).body()).get("totalElements").asInt()).isZero();
        assertError(as(testUsers.staff(),"POST","/me/beneficiaries",beneficiary(),null),403,"FORBIDDEN");
        assertError(as(owner,"POST","/me/beneficiaries","{\"destinationAccountId\":\""+UUID.randomUUID()+"\"}",null),404,"ACCOUNT_NOT_FOUND");
        assertThat(count("beneficiaries")).isEqualTo(1);
    }
    @Test void concurrentBeneficiaryCreationUsesPartialUniqueIndex() throws Exception {
        String token=testUsers.customer(SOURCE);
        var responses=together(asRequest(token,"POST","/me/beneficiaries",beneficiary(),null),
            asRequest(token,"POST","/me/beneficiaries",beneficiary(),null));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201,409);
        assertError(responses.stream().filter(r->r.statusCode()==409).findFirst().orElseThrow(),409,"BENEFICIARY_CONFLICT");
        assertThat(count("beneficiaries")).isEqualTo(1); assertThat(actions("BENEFICIARY_CREATED")).isEqualTo(1);
    }
    @Test void auditSuccessAndFailureHaveActorTargetOutcomeAndRequestIdWithoutRawPayload() throws Exception {
        assertThat(transfer("audit-success","1000").statusCode()).isEqualTo(201);
        assertError(transfer("audit-failure","2000000"),409,"INSUFFICIENT_FUNDS");
        UUID owner=jdbc.queryForObject("SELECT customer_id FROM accounts WHERE id=?",UUID.class,SOURCE);
        UUID actor=testUsers.ensure(RoleCode.CUSTOMER,owner);
        var rows=jdbc.queryForList("SELECT * FROM audit_logs WHERE action LIKE 'TRANSFER_%' ORDER BY created_at,id");
        assertThat(rows).hasSize(2).allSatisfy(row->{
            assertThat(row.get("actor_id")).isEqualTo(actor);
            assertThat(row.get("request_id")).isEqualTo("transfer-it");
            assertThat(row.get("details").toString()).doesNotContain("password","token","Demo transfer","audit-success","audit-failure");
        });
        var success=rows.stream().filter(r->r.get("outcome").equals("SUCCESS")).findFirst().orElseThrow();
        assertThat(success.get("target_type")).isEqualTo("TRANSACTION");
        var failure=rows.stream().filter(r->r.get("outcome").equals("FAILURE")).findFirst().orElseThrow();
        assertThat(failure.get("target_id")).isEqualTo(SOURCE.toString());
        assertThat(failure.get("details").toString()).contains("INSUFFICIENT_FUNDS");
    }
    @Test void staffCanReadOnlyTheirOwnAuditAndAdminCanFilterAll() throws Exception {
        String staff=testUsers.staff(),admin=testUsers.bearer(RoleCode.ADMIN,null);
        assertThat(as(staff,"PATCH","/accounts/"+DESTINATION+"/status","{\"status\":\"FROZEN\"}",null).statusCode()).isEqualTo(200);
        assertThat(limit("1000","2000").statusCode()).isEqualTo(200);
        var staffLogs=json.readTree(as(staff,"GET","/audit-logs",null,null).body());
        assertThat(staffLogs.get("totalElements").asInt()).isEqualTo(1);
        assertThat(staffLogs.get("content").get(0).get("action").asText()).isEqualTo("ACCOUNT_STATUS_CHANGED");
        var adminLogs=json.readTree(as(admin,"GET","/audit-logs",null,null).body());
        assertThat(adminLogs.get("totalElements").asInt()).isEqualTo(2);
        var filtered=json.readTree(as(admin,"GET","/audit-logs?action=TRANSFER_LIMIT_UPDATED&outcome=SUCCESS",null,null).body());
        assertThat(filtered.get("totalElements").asInt()).isEqualTo(1);
        assertError(as(testUsers.customer(SOURCE),"GET","/audit-logs",null,null),403,"FORBIDDEN");
        assertError(as(testUsers.bearer(RoleCode.RISK_OFFICER,null),"GET","/audit-logs",null,null),403,"FORBIDDEN");
        assertError(as(admin,"GET","/audit-logs?from=2026-10-28T00:00:00Z&to=2026-10-27T00:00:00Z",null,null),400,"INVALID_TIME_RANGE");
    }
    @Test void loginHistoryIsDurableAndStoresHmacInsteadOfCredentialsOrRawUsername() throws Exception {
        UUID user=testUsers.ensure(RoleCode.ADMIN,null);
        String username=testUsers.username(RoleCode.ADMIN,null),password=testUsers.password();
        String good="{\"username\":\""+username+"\",\"password\":\""+password+"\"}";
        assertThat(as(null,"POST","/auth/login",good,null).statusCode()).isEqualTo(200);
        assertError(as(null,"POST","/auth/login","{\"username\":\""+username+"\",\"password\":\"wrong-secret\"}",null),401,"INVALID_CREDENTIALS");
        assertError(as(null,"POST","/auth/login","{\"username\":\"unknown-person\",\"password\":\"wrong-secret\"}",null),401,"INVALID_CREDENTIALS");
        var rows=jdbc.queryForList("SELECT * FROM login_history ORDER BY created_at,id");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("success")).isEqualTo(true);
        assertThat(rows.get(0).get("user_id")).isEqualTo(user);
        assertThat(rows.get(0).get("username_fingerprint")).isEqualTo(rows.get(1).get("username_fingerprint"));
        assertThat(rows.get(2).get("username_fingerprint")).isNotEqualTo(rows.get(0).get("username_fingerprint"));
        assertThat(rows).allSatisfy(row->{
            assertThat(row.get("username_fingerprint").toString()).matches("[0-9a-f]{64}");
            assertThat(row.get("request_id")).isEqualTo("transfer-it");
        });
        assertThat(rows.toString()).doesNotContain(username,password,"wrong-secret","unknown-person");
        String audit=jdbc.queryForList("SELECT details::text FROM audit_logs",String.class).toString();
        assertThat(audit).doesNotContain(username,password,"wrong-secret","unknown-person","accessToken","passwordHash");
        assertThat(actions("LOGIN_SUCCEEDED")).isEqualTo(1); assertThat(actions("LOGIN_FAILED")).isEqualTo(2);
    }
    @Test void adminUserMutationsAreAuditedWithoutPasswordOrUsernameCopies() throws Exception {
        String token=testUsers.bearer(RoleCode.ADMIN,null),password="Phase6-test-Password-123";
        var created=as(token,"POST","/users","{\"username\":\"phase6.newstaff\",\"password\":\""+password+"\",\"roles\":[\"BANK_STAFF\"]}",null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id=json.readTree(created.body()).get("id").asText();
        assertThat(as(token,"PATCH","/users/"+id+"/roles","{\"roles\":[\"RISK_OFFICER\"]}",null).statusCode()).isEqualTo(200);
        assertThat(as(token,"PATCH","/users/"+id+"/status","{\"enabled\":false}",null).statusCode()).isEqualTo(200);
        assertThat(actions("USER_CREATED")).isEqualTo(1);
        assertThat(actions("USER_ROLES_CHANGED")).isEqualTo(1);
        assertThat(actions("USER_STATUS_CHANGED")).isEqualTo(1);
        var rows=jdbc.queryForList("SELECT actor_id,target_id,details::text FROM audit_logs WHERE target_type='USER'");
        assertThat(rows).hasSize(3).allSatisfy(row->{
            assertThat(row.get("actor_id")).isEqualTo(testUsers.ensure(RoleCode.ADMIN,null));
            assertThat(row.get("target_id")).isEqualTo(id);
        });
        assertThat(rows.toString()).doesNotContain(password,"phase6.newstaff","password_hash","accessToken");
    }
}
