package com.minh.banking.phase7;

import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import com.minh.banking.user.enums.RoleCode;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class PortalQueryIT extends TransferIntegrationTestSupport {
    @Test void customerCanResolveDestinationByNumberAndIdWithoutPrivateAccountFields() throws Exception {
        String number=jdbc.queryForObject("SELECT account_number FROM accounts WHERE id=?",String.class,TREASURY);
        for (String path:new String[]{"/transfer-recipients?accountNumber="+number,"/transfer-recipients/"+TREASURY}) {
            var response=request("GET",path,null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var body=json.readTree(response.body());
            assertThat(body.size()).isEqualTo(4);
            assertThat(body.get("id").asText()).isEqualTo(TREASURY.toString());
            assertThat(body.get("accountNumber").asText()).isEqualTo(number);
            assertThat(response.body()).doesNotContain("balance","customerId","fullName","email","phone","password");
        }
        // Lookup does not change the ownership policy of the full account endpoint.
        assertError(request("GET","/accounts/"+TREASURY,null),404,"ACCOUNT_NOT_FOUND");
    }
    @Test void recipientValidationAndMissingAccountUseStandardErrors() throws Exception {
        assertError(request("GET","/transfer-recipients?accountNumber=123456789012345678901234567890",null),404,"ACCOUNT_NOT_FOUND");
        assertError(request("GET","/transfer-recipients?accountNumber=",null),400,"INVALID_REQUEST");
        assertError(request("GET","/transfer-recipients?accountNumber="+"1".repeat(31),null),400,"INVALID_REQUEST");
        assertError(request("GET","/transfer-recipients/not-a-uuid",null),400,"INVALID_REQUEST");
    }
    @Test void internalRolesAndAnonymousCannotEnumerateRecipients() throws Exception {
        for (RoleCode role:new RoleCode[]{RoleCode.ADMIN,RoleCode.BANK_STAFF,RoleCode.RISK_OFFICER}) {
            var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/transfer-recipients/"+TREASURY))
                .header("Authorization",testUsers.bearer(role,null)).header("X-Request-Id","transfer-it").GET().build(),HttpResponse.BodyHandlers.ofString());
            assertError(response,403,"FORBIDDEN");
        }
        var anonymous=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/transfer-recipients/"+TREASURY))
            .header("X-Request-Id","transfer-it").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertError(anonymous,401,"UNAUTHENTICATED");
    }
    @Test void beneficiaryProjectionIncludesNumberAndPreservesOwnerScope() throws Exception {
        var created=request("POST","/me/beneficiaries","{\"destinationAccountId\":\""+TREASURY+"\",\"nickname\":\"Saved recipient\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String number=jdbc.queryForObject("SELECT account_number FROM accounts WHERE id=?",String.class,TREASURY);
        assertThat(json.readTree(created.body()).get("destinationAccountNumber").asText()).isEqualTo(number);
        var listed=request("GET","/me/beneficiaries",null);
        assertThat(json.readTree(listed.body()).get("content").get(0).get("destinationAccountNumber").asText()).isEqualTo(number);
        assertThat(listed.body()).doesNotContain("balance","customerId","fullName","password");
    }
}
