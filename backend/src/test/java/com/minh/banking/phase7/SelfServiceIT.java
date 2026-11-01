package com.minh.banking.phase7;

import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import com.minh.banking.user.enums.RoleCode;
import com.minh.banking.auth.service.Totp;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class SelfServiceIT extends TransferIntegrationTestSupport {
    @Autowired Totp totp;
    private HttpResponse<String> as(String token,String method,String path,Object body)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("X-Request-Id","transfer-it").header("Idempotency-Key",UUID.randomUUID().toString());
        if(token!=null)builder.header("Authorization",token);
        String value=body==null?null:body instanceof String?(String)body:json.writeValueAsString(body);
        return http.send(builder.method(method,value==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(value)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String customer(){return testUsers.customer(SOURCE);}
    private UUID customerId(){return jdbc.queryForObject("SELECT customer_id FROM accounts WHERE id=?",UUID.class,SOURCE);}
    private UUID user(){return testUsers.ensure(RoleCode.CUSTOMER,customerId());}
    private String supportTicket(String token)throws Exception{
        var transfer=request("POST","/transfers",payload(SOURCE,DESTINATION,"1000"));
        assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(201);
        String tx=json.readTree(transfer.body()).get("transaction").get("id").asText();
        var created=as(token,"POST","/me/support-tickets",Map.of("transactionId",tx,"category","OTHER","subject","Support notification test","message","Please review"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return json.readTree(created.body()).get("ticket").get("id").asText();
    }
    @Test void staffStatusAndMessagesNotifyOnlyTheTicketOwnerAndSummaryIsPrivate()throws Exception{
        String token=customer(),id=supportTicket(token),staff=testUsers.staff(),other=testUsers.customer(TREASURY);
        var changed=as(staff,"PATCH","/staff/support-tickets/"+id,Map.of("expectedVersion",0,"status","IN_PROGRESS"));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(as(staff,"POST","/staff/support-tickets/"+id+"/messages",Map.of("message","CSKH is reviewing the transfer")).statusCode()).isEqualTo(200);
        var inbox=as(token,"GET","/me/notifications?unreadOnly=true",null);
        var support=new ArrayList<tools.jackson.databind.JsonNode>();
        for(var item:json.readTree(inbox.body()).get("content"))if(item.get("type").asText().startsWith("SUPPORT_"))support.add(item);
        assertThat(support).hasSize(2);
        assertThat(support.stream().map(n->n.get("type").asText()).toList()).containsExactlyInAnyOrder("SUPPORT_STATUS_CHANGED","SUPPORT_REPLY");
        for(var item:support)assertThat(item.get("content").get("ticketId").asText()).isEqualTo(id);
        var reply=support.stream().filter(n->n.get("type").asText().equals("SUPPORT_REPLY")).findFirst().orElseThrow();
        assertThat(reply.get("content").get("messagePreview").asText()).isEqualTo("CSKH is reviewing the transfer");
        var ownSummary=json.readTree(as(token,"GET","/me/notifications/summary",null).body());
        assertThat(ownSummary.get("unreadCount").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(ownSummary.get("latest").get("content").get("ticketId").asText()).isEqualTo(id);
        var otherSummary=as(other,"GET","/me/notifications/summary",null);assertThat(otherSummary.statusCode()).isEqualTo(200);
        assertThat(otherSummary.body()).doesNotContain(id,"CSKH is reviewing");
        assertError(as(other,"PATCH","/me/notifications/"+reply.get("id").asText()+"/read",null),404,"NOTIFICATION_NOT_FOUND");
        assertThat(as(token,"PATCH","/me/notifications/"+reply.get("id").asText()+"/read",null).statusCode()).isEqualTo(204);
        var after=json.readTree(as(token,"GET","/me/notifications/summary",null).body());
        assertThat(after.get("unreadCount").asLong()).isEqualTo(ownSummary.get("unreadCount").asLong()-1);
        assertError(as(null,"GET","/me/notifications/summary",null),401,"UNAUTHENTICATED");
    }
    @Test void customerMessagesDoNotNotifySelfAndStaffRepliesDoNotReopenResolvedTickets()throws Exception{
        String token=customer(),id=supportTicket(token),staff=testUsers.staff();
        assertThat(as(staff,"PATCH","/staff/support-tickets/"+id,Map.of("expectedVersion",0,"status","RESOLVED","message","Initial resolution")).statusCode()).isEqualTo(200);
        assertThat(as(staff,"POST","/staff/support-tickets/"+id+"/messages",Map.of("message","Additional guidance")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM support_tickets WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("RESOLVED");
        long before=jdbc.queryForObject("SELECT count(*) FROM notifications WHERE content->>'ticketId'=?",Long.class,id);
        assertThat(before).isEqualTo(2);
        assertError(as(staff,"PATCH","/staff/support-tickets/"+id,Map.of("expectedVersion",0,"status","IN_PROGRESS")),409,"INVALID_WORKFLOW_STATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE content->>'ticketId'=?",Long.class,id)).isEqualTo(before);
        assertThat(as(token,"POST","/me/support-tickets/"+id+"/messages",Map.of("message","I need more help")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM support_tickets WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE content->>'ticketId'=?",Long.class,id)).isEqualTo(before);
    }
    @Test void ticketUpdateRollsBackWhenItsInboxNotificationCannotBeSaved()throws Exception{
        String token=customer(),id=supportTicket(token),staff=testUsers.staff();
        jdbc.execute("ALTER TABLE notifications ADD CONSTRAINT qa_reject_support_notice CHECK(type NOT LIKE 'SUPPORT_%') NOT VALID");
        try {
            assertError(as(staff,"PATCH","/staff/support-tickets/"+id,Map.of("expectedVersion",0,"status","RESOLVED","message","This must roll back")),409,"DATA_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT status FROM support_tickets WHERE id=?",String.class,UUID.fromString(id))).isEqualTo("OPEN");
            assertThat(jdbc.queryForObject("SELECT version FROM support_tickets WHERE id=?",Long.class,UUID.fromString(id))).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM support_messages WHERE ticket_id=?",Long.class,UUID.fromString(id))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE content->>'ticketId'=?",Long.class,id)).isZero();
        } finally { jdbc.execute("ALTER TABLE notifications DROP CONSTRAINT qa_reject_support_notice"); }
    }
    @Test void publicRecoveryOptionsDescribeDeliveryWithoutExposingCredentials()throws Exception{
        var response=as(null,"GET","/auth/recovery-options",null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var body=json.readTree(response.body());
        assertThat(body.get("deliveryMode").asText()).isEqualTo("UNAVAILABLE");
        assertThat(body.get("localMailboxPath").isNull()).isTrue();
        assertThat(response.body()).doesNotContain("token","secret","password","username");
    }
    private String login(String password,String otp)throws Exception{
        var body=new HashMap<String,String>();body.put("username",testUsers.username(RoleCode.CUSTOMER,customerId()));body.put("password",password);if(otp!=null)body.put("otp",otp);
        var response=as(null,"POST","/auth/login",body);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return "Bearer "+json.readTree(response.body()).get("accessToken").asText();
    }
    @Test void sessionsAreOwnerScopedAndRevocationsApplyOnNextRequest()throws Exception{
        String first=customer(),second=customer(),other=testUsers.customer(TREASURY);
        var response=as(first,"GET","/me/security/sessions",null);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var list=json.readTree(response.body());assertThat(list.size()).isEqualTo(2);
        String foreign=json.readTree(as(other,"GET","/me/security/sessions",null).body()).get(0).get("id").asText();
        assertError(as(first,"DELETE","/me/security/sessions/"+foreign,null),404,"RESOURCE_NOT_FOUND");
        assertThat(as(first,"DELETE","/me/security/sessions",null).statusCode()).isEqualTo(204);
        assertThat(as(first,"GET","/me",null).statusCode()).isEqualTo(200);
        assertError(as(second,"GET","/me",null),401,"UNAUTHENTICATED");assertThat(as(other,"GET","/me",null).statusCode()).isEqualTo(200);
        assertThat(as(first,"POST","/me/security/logout",null).statusCode()).isEqualTo(204);assertError(as(first,"GET","/me",null),401,"UNAUTHENTICATED");
    }
    @Test void passwordChangeRequiresProofAndRevokesAllSessions()throws Exception{
        String token=customer();String old=testUsers.password(),next="Changed-local-password-123!";
        assertError(as(token,"POST","/me/security/password",Map.of("password","wrong","newPassword",next)),400,"SECURITY_VERIFICATION_FAILED");
        assertThat(as(token,"GET","/me",null).statusCode()).isEqualTo(200);
        assertThat(as(token,"POST","/me/security/password",Map.of("password",old,"newPassword",next)).statusCode()).isEqualTo(204);
        assertError(as(token,"GET","/me",null),401,"UNAUTHENTICATED");
        assertError(as(null,"POST","/auth/login",Map.of("username",testUsers.username(RoleCode.CUSTOMER,customerId()),"password",old)),401,"INVALID_CREDENTIALS");
        assertThat(as(login(next,null),"GET","/me",null).statusCode()).isEqualTo(200);
    }
    @Test void totpEnrollmentRequiresValidCodeAndRecoveryCodesAreSingleUse()throws Exception{
        String token=customer(),other=customer();
        var setup=as(token,"POST","/me/security/totp/setup",Map.of("password",testUsers.password()));assertThat(setup.statusCode()).as(setup.body()).isEqualTo(200);
        String secret=json.readTree(setup.body()).get("secret").asText();
        assertThat(jdbc.queryForObject("SELECT pending_secret FROM user_security WHERE user_id=?",String.class,user())).doesNotContain(secret);
        String code=Totp.code(secret,System.currentTimeMillis()/30000,6);
        var enabled=as(token,"POST","/me/security/totp/enable",Map.of("code",code));assertThat(enabled.statusCode()).as(enabled.body()).isEqualTo(200);
        var codes=json.readTree(enabled.body()).get("recoveryCodes");assertThat(codes.size()).isEqualTo(8);
        assertError(as(other,"GET","/me",null),401,"UNAUTHENTICATED");
        assertError(as(null,"POST","/auth/login",Map.of("username",testUsers.username(RoleCode.CUSTOMER,customerId()),"password",testUsers.password())),400,"MFA_REQUIRED");
        String recovery=codes.get(0).asText();String authenticated=login(testUsers.password(),recovery);
        assertThat(as(authenticated,"GET","/me",null).statusCode()).isEqualTo(200);
        assertError(as(null,"POST","/auth/login",Map.of("username",testUsers.username(RoleCode.CUSTOMER,customerId()),"password",testUsers.password(),"otp",recovery)),401,"INVALID_CREDENTIALS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recovery_codes WHERE user_id=?",Long.class,user())).isEqualTo(7);
    }
    @Test void resetTokenExpiresIsSingleUseAndNeverBypassesMfa()throws Exception{
        String token=customer();String raw=UUID.randomUUID().toString();UUID user=user();
        jdbc.update("INSERT INTO password_reset_tokens(token_hash,user_id,expires_at) VALUES (?,?,now()+interval '10 minutes')",Totp.hash(raw),user);
        String next="Reset-password-for-test-123!";
        assertError(as(null,"POST","/auth/reset-password",Map.of("token","invalid","newPassword",next)),400,"INVALID_RESET_TOKEN");
        assertThat(as(null,"POST","/auth/reset-password",Map.of("token",raw,"newPassword",next)).statusCode()).isEqualTo(204);
        assertError(as(token,"GET","/me",null),401,"UNAUTHENTICATED");assertError(as(null,"POST","/auth/reset-password",Map.of("token",raw,"newPassword",next)),400,"INVALID_RESET_TOKEN");
        assertThat(as(login(next,null),"GET","/me",null).statusCode()).isEqualTo(200);
    }
    @Test void resetRequiresExistingSecondFactorAndPreservesTokenOnFailure()throws Exception{
        customer();UUID user=user();String secret=totp.newSecret(),raw=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO user_security(user_id,totp_enabled,totp_secret) VALUES (?,true,?)",user,totp.encrypt(user,secret));
        jdbc.update("INSERT INTO password_reset_tokens(token_hash,user_id,expires_at) VALUES (?,?,now()+interval '10 minutes')",Totp.hash(raw),user);
        assertError(as(null,"POST","/auth/reset-password",Map.of("token",raw,"newPassword","Another-new-password-123!")),400,"SECURITY_VERIFICATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT used_at IS NULL FROM password_reset_tokens WHERE token_hash=?",Boolean.class,Totp.hash(raw))).isTrue();
    }
    @Test void templatesHaveOwnerScopeAndNeverMoveMoney()throws Exception{
        String token=customer();var before=accountSnapshot();
        var created=as(token,"POST","/me/transfer-templates",Map.of("name","Rent","sourceAccountId",SOURCE,"destinationAccountId",TREASURY,"amount","1000","description","Monthly rent"));assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id=json.readTree(created.body()).get("id").asText();assertThat(accountSnapshot()).isEqualTo(before);
        assertError(as(testUsers.customer(TREASURY),"GET","/me/transfer-templates/"+id,null),404,"TEMPLATE_NOT_FOUND");
        assertError(as(testUsers.customer(TREASURY),"DELETE","/me/transfer-templates/"+id,null),404,"TEMPLATE_NOT_FOUND");
        assertThat(as(token,"DELETE","/me/transfer-templates/"+id,null).statusCode()).isEqualTo(204);
    }
    @Test void supportWorkflowIsPrivateVersionedAndDoesNotRefund()throws Exception{
        String token=customer();var transfer=request("POST","/transfers",payload(SOURCE,DESTINATION,"1000"));assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(201);
        String tx=json.readTree(transfer.body()).get("transaction").get("id").asText();var balances=accountSnapshot();
        var created=as(token,"POST","/me/support-tickets",Map.of("transactionId",tx,"category","OTHER","subject","Please check","message","Please review this transfer"));assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id=json.readTree(created.body()).get("ticket").get("id").asText();
        assertError(as(testUsers.customer(TREASURY),"GET","/me/support-tickets/"+id,null),404,"TICKET_NOT_FOUND");
        assertError(as(token,"PATCH","/me/support-tickets/"+id,Map.of("expectedVersion",0,"status","RESOLVED","message","Self resolved")),403,"FORBIDDEN");
        String staff=testUsers.staff();
        assertThat(as(staff,"PATCH","/staff/support-tickets/"+id,Map.of("expectedVersion",0,"status","IN_PROGRESS")).statusCode()).isEqualTo(200);
        assertError(as(staff,"PATCH","/staff/support-tickets/"+id,Map.of("expectedVersion",0,"status","RESOLVED","message","Stale")),409,"INVALID_WORKFLOW_STATE");
        assertThat(as(staff,"PATCH","/staff/support-tickets/"+id,Map.of("expectedVersion",1,"status","RESOLVED","message","Reviewed; no money was moved")).statusCode()).isEqualTo(200);
        assertThat(as(token,"PATCH","/me/support-tickets/"+id,Map.of("expectedVersion",2,"status","CLOSED")).statusCode()).isEqualTo(200);
        assertThat(accountSnapshot()).isEqualTo(balances);
    }
    @Test void personalLimitsEnforceReductionsAndRequireAdminApprovalForIncrease()throws Exception{
        String token=customer();var lower=Map.of("accountId",SOURCE,"perTransferAmount","500","dailyAmount","1000");
        assertThat(as(token,"PUT","/me/personal-transfer-limits",lower).statusCode()).isEqualTo(200);
        assertError(request("POST","/transfers",payload(SOURCE,DESTINATION,"600")),409,"PER_TRANSFER_LIMIT_EXCEEDED");
        var increase=Map.of("accountId",SOURCE,"perTransferAmount","1000","dailyAmount","2000","reason","Need higher limit");
        assertError(as(token,"PUT","/me/personal-transfer-limits",increase),409,"LIMIT_INCREASE_REQUIRED");
        var created=as(token,"POST","/me/limit-requests",increase);assertThat(created.statusCode()).as(created.body()).isEqualTo(201);String id=json.readTree(created.body()).get("id").asText();
        assertError(as(token,"PATCH","/staff/limit-requests/"+id,Map.of("approve",true,"note","Self approved")),403,"FORBIDDEN");
        String admin=testUsers.bearer(RoleCode.ADMIN,null);
        assertThat(as(admin,"PATCH","/staff/limit-requests/"+id,Map.of("approve",true,"note","Approved after review")).statusCode()).isEqualTo(200);
        assertError(as(admin,"PATCH","/staff/limit-requests/"+id,Map.of("approve",true,"note","Duplicate")),409,"INVALID_WORKFLOW_STATE");
        assertThat(request("POST","/transfers",payload(SOURCE,DESTINATION,"600")).statusCode()).isEqualTo(201);
    }
}
