package com.minh.banking.security;

import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import com.minh.banking.user.enums.RoleCode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class SecurityAuthorizationIT extends TransferIntegrationTestSupport {
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;

    @Test
    void everyExistingBusinessRouteRequiresBearerAuthentication() throws Exception {
        for (String path : List.of("/me", "/me/accounts", "/me/transactions", "/customers", "/accounts/" + SOURCE,
                "/customers/" + owner() + "/accounts", "/accounts/" + SOURCE + "/transactions",
                "/transactions/" + UUID.randomUUID(), "/users", "/roles")) {
            var response = as(null, "GET", path, null);
            assertError(response, 401, "UNAUTHENTICATED");
            assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        }
        assertError(as(null, "POST", "/transfers", payload(SOURCE, DESTINATION, "1")), 401, "UNAUTHENTICATED");
        assertError(as("Basic Zm9vOmJhcg==", "GET", "/me", null), 401, "UNAUTHENTICATED");
        assertLedgerConsistent(0);
    }

    @Test
    void loginNormalizesUsernameIssuesShortTokenAndNeverReturnsPasswordHash() throws Exception {
        UUID userId = testUsers.ensure(RoleCode.CUSTOMER, owner());
        String username = testUsers.username(RoleCode.CUSTOMER, owner());
        var response = login(username.toUpperCase(Locale.ROOT), testUsers.password());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(body.get("user").get("id").asText()).isEqualTo(userId.toString());
        assertThat(body.get("user").has("passwordHash")).isFalse();
        assertThat(response.body()).doesNotContain(testUsers.password(), "pbkdf2", "password_hash");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        String token = "Bearer " + body.get("accessToken").asText();
        assertThat(as(token, "GET", "/me", null).statusCode()).isEqualTo(200);
        assertError(login(username, "incorrect-password"), 401, "INVALID_CREDENTIALS");
        assertError(login("unknown.user", "incorrect-password"), 401, "INVALID_CREDENTIALS");
        jdbc.update("update users set enabled=false where id=?", userId);
        assertError(login(username, testUsers.password()), 401, "INVALID_CREDENTIALS");
        assertError(as(token, "GET", "/me", null), 401, "UNAUTHENTICATED");
    }

    @Test
    void rejectsExpiredFutureWrongIssuerAudienceMissingClaimsAndInvalidSignatureTokens() throws Exception {
        UUID id = testUsers.ensure(RoleCode.CUSTOMER, owner());
        Instant now = Instant.now();
        var overrides = List.<Map<String, Object>>of(
            Map.of("iat", now.minusSeconds(600), "nbf", now.minusSeconds(600), "exp", now.minusSeconds(300)),
            Map.of("nbf", now.plusSeconds(300)), Map.of("iss", "wrong-issuer"), Map.of("aud", List.of("wrong-audience")),
            Map.of("sub", "not-a-uuid"));
        for (var changed : overrides) assertError(as(mint(id, changed, Set.of()), "GET", "/me", null), 401, "UNAUTHENTICATED");
        for (String missing : List.of("exp", "iat", "nbf", "aud", "sub", "iss")) {
            assertError(as(mint(id, Map.of(), Set.of(missing)), "GET", "/me", null), 401, "UNAUTHENTICATED");
        }
        String raw = mint(id, Map.of(), Set.of());
        int signature = raw.lastIndexOf('.') + 1;
        String corrupted = raw.substring(0, signature) + (raw.charAt(signature) == 'A' ? 'B' : 'A') + raw.substring(signature + 1);
        assertError(as(corrupted, "GET", "/me", null), 401, "UNAUTHENTICATED");
        assertError(as("Bearer malformed", "GET", "/me", null), 401, "UNAUTHENTICATED");
    }

    @Test
    void customerCannotReadOtherAccountsHistoryOrSelectAnotherCustomerThroughFilters() throws Exception {
        var other = otherCustomer();
        String customer = testUsers.customer(SOURCE);
        var privateAccount = as(customer, "GET", "/accounts/" + other.account(), null);
        var missingAccount = as(customer, "GET", "/accounts/" + UUID.randomUUID(), null);
        assertError(privateAccount, 404, "ACCOUNT_NOT_FOUND");
        assertThat(json.readTree(privateAccount.body()).get("message")).isEqualTo(json.readTree(missingAccount.body()).get("message"));
        assertError(as(customer, "GET", "/accounts/" + other.account() + "/transactions", null), 404, "ACCOUNT_NOT_FOUND");
        assertError(as(customer, "GET", "/customers/" + other.customer() + "/accounts", null), 404, "CUSTOMER_NOT_FOUND");
        var mine = json.readTree(as(customer, "GET", "/me/accounts?customerId=" + other.customer(), null).body());
        assertThat(mine.get("totalElements").asInt()).isEqualTo(2);
        for (var account : mine.get("content")) assertThat(account.get("customerId").asText()).isEqualTo(owner().toString());
        var myHistory = json.readTree(as(customer, "GET", "/me/transactions?accountId=" + other.account(), null).body());
        assertThat(myHistory.get("totalElements").asInt()).isEqualTo(1);
        var privateOpening = jdbc.queryForObject("select id from transactions where destination_account_id=?", UUID.class, other.account());
        assertError(as(customer, "GET", "/transactions/" + privateOpening, null), 404, "TRANSACTION_NOT_FOUND");
    }

    @Test
    void customerCannotDebitAnotherCustomerAndCannotSupplyActorIdentity() throws Exception {
        var other = otherCustomer();
        var snapshot = accountSnapshot();
        assertError(as(testUsers.customer(SOURCE), "POST", "/transfers", payload(other.account(), SOURCE, "100")),
            404, "ACCOUNT_NOT_FOUND");
        assertThat(accountSnapshot()).isEqualTo(snapshot);
        String forged = payload(SOURCE, DESTINATION, "100").replace("\"description\":", "\"userId\":\"" + UUID.randomUUID() + "\",\"description\":");
        assertError(as(testUsers.customer(SOURCE), "POST", "/transfers", forged), 400, "INVALID_REQUEST");
        assertLedgerConsistent(0);
    }

    @Test
    void internalRolesHaveSeparatePermissionsAndNeverAutomaticallyDebitCustomerMoney() throws Exception {
        String staff = testUsers.staff();
        String admin = testUsers.bearer(RoleCode.ADMIN, null);
        String risk = testUsers.bearer(RoleCode.RISK_OFFICER, null);
        String customer = testUsers.customer(SOURCE);
        for (String internal : List.of(staff, admin, risk)) {
            assertError(as(internal, "POST", "/transfers", payload(SOURCE, DESTINATION, "1")), 403, "FORBIDDEN");
            assertThat(as(internal, "GET", "/accounts/" + SOURCE, null).statusCode()).isEqualTo(200);
        }
        assertError(as(staff, "GET", "/users", null), 403, "FORBIDDEN");
        assertError(as(staff, "PATCH", "/users/" + UUID.randomUUID() + "/roles", "{\"roles\":[\"ADMIN\"]}"), 403, "FORBIDDEN");
        assertError(as(risk, "GET", "/customers", null), 403, "FORBIDDEN");
        assertError(as(risk, "PATCH", "/accounts/" + SOURCE + "/status", "{\"status\":\"FROZEN\"}"), 403, "FORBIDDEN");
        assertError(as(customer, "PATCH", "/accounts/" + SOURCE + "/status", "{\"status\":\"FROZEN\"}"), 403, "FORBIDDEN");
        assertError(as(customer, "POST", "/customers", "{\"customerCode\":\"NOPE\",\"fullName\":\"Nope\"}"), 403, "FORBIDDEN");
        assertError(as(customer, "POST", "/customers/" + owner() + "/accounts", "{\"currency\":\"VND\"}"), 403, "FORBIDDEN");
        assertThat(as(admin, "GET", "/users", null).statusCode()).isEqualTo(200);
        assertThat(as(admin, "GET", "/roles", null).statusCode()).isEqualTo(200);
        assertLedgerConsistent(0);
    }

    @Test
    void validTransferAndDetailNeverRevealCounterpartyBalanceToEitherCustomer() throws Exception {
        var other = otherCustomer();
        String customer = testUsers.customer(SOURCE);
        var response = as(customer, "POST", "/transfers", payload(SOURCE, other.account(), "200000"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var body = json.readTree(response.body());
        String id = body.get("transaction").get("id").asText();
        assertThat(body.get("entries").get(0).get("balanceAfter").asText()).isEqualTo("800000.00");
        assertThat(body.get("entries").get(1).get("balanceAfter").isNull()).isTrue();
        var otherView = json.readTree(as(testUsers.customer(other.account()), "GET", "/transactions/" + id, null).body());
        assertThat(otherView.get("entries").get(0).get("balanceAfter").isNull()).isTrue();
        assertThat(otherView.get("entries").get(1).get("balanceAfter").asText()).isEqualTo("700000.00");
        var internal = json.readTree(as(testUsers.staff(), "GET", "/transactions/" + id, null).body());
        assertThat(internal.get("entries").get(0).get("balanceAfter").isNull()).isFalse();
        assertThat(internal.get("entries").get(1).get("balanceAfter").isNull()).isFalse();
        assertLedgerConsistent(1);
    }

    @Test
    void adminCreatesUserAndOldTokenImmediatelyFollowsDatabaseRoleAndEnabledChanges() throws Exception {
        String admin = testUsers.bearer(RoleCode.ADMIN, null);
        String password = testUsers.password();
        String create = "{\"username\":\"new.staff\",\"password\":\"" + password + "\",\"roles\":[\"BANK_STAFF\"]}";
        var response = as(admin, "POST", "/users", create);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.body()).doesNotContain(password, "passwordHash", "password_hash", "pbkdf2");
        UUID id = UUID.fromString(json.readTree(response.body()).get("id").asText());
        String hash = jdbc.queryForObject("select password_hash from users where id=?", String.class, id);
        assertThat(hash).startsWith("{pbkdf2").isNotEqualTo(password);
        assertThat(passwords.matches(password, hash)).isTrue();
        assertError(as(admin, "POST", "/users", create), 409, "USER_CONFLICT");
        var loggedIn = login("new.staff", password);
        assertThat(loggedIn.statusCode()).isEqualTo(200);
        String oldToken = "Bearer " + json.readTree(loggedIn.body()).get("accessToken").asText();
        assertThat(as(oldToken, "GET", "/customers", null).statusCode()).isEqualTo(200);
        assertThat(as(admin, "PATCH", "/users/" + id + "/roles", "{\"roles\":[\"RISK_OFFICER\"]}").statusCode()).isEqualTo(200);
        assertError(as(oldToken, "GET", "/customers", null), 403, "FORBIDDEN");
        assertThat(as(oldToken, "GET", "/accounts/" + SOURCE, null).statusCode()).isEqualTo(200);
        assertThat(as(admin, "PATCH", "/users/" + id + "/status", "{\"enabled\":false}").statusCode()).isEqualTo(200);
        assertError(as(oldToken, "GET", "/me", null), 401, "UNAUTHENTICATED");
        assertError(login("new.staff", password), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void customerRoleRequiresUniqueLinkAndCannotMixWithInternalRoles() throws Exception {
        String admin = testUsers.bearer(RoleCode.ADMIN, null);
        String prefix = "{\"username\":\"client.a\",\"password\":\"" + testUsers.password() + "\",";
        assertError(as(admin, "POST", "/users", prefix + "\"roles\":[\"CUSTOMER\"]}"), 400, "CUSTOMER_LINK_REQUIRED");
        assertError(as(admin, "POST", "/users", prefix + "\"customerId\":\"" + owner() + "\",\"roles\":[\"CUSTOMER\",\"ADMIN\"]}"),
            400, "INVALID_USER_ROLES");
        String valid = prefix + "\"customerId\":\"" + owner() + "\",\"roles\":[\"CUSTOMER\"]}";
        assertThat(as(admin, "POST", "/users", valid).statusCode()).isEqualTo(201);
        assertError(as(admin, "POST", "/users", valid.replace("client.a", "client.b")), 409, "USER_CONFLICT");
        assertError(as(admin, "POST", "/users", prefix + "\"roles\":[]}"), 400, "VALIDATION_ERROR");
        assertError(as(admin, "POST", "/users", prefix + "\"roles\":[\"SUPER_ADMIN\"]}"), 400, "INVALID_REQUEST");
        assertError(as(admin, "POST", "/users", prefix + "\"customerId\":\"" + UUID.randomUUID() + "\",\"roles\":[\"CUSTOMER\"]}"),
            404, "CUSTOMER_NOT_FOUND");
    }

    @Test
    void signedRoleClaimsCannotEscalateDatabaseCustomerToAdmin() throws Exception {
        UUID customer = testUsers.ensure(RoleCode.CUSTOMER, owner());
        // Keep a valid registered session so this checks role authority, independently of session admission.
        UUID session=UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions(id,user_id,expires_at) VALUES (?,?,now()+interval '15 minutes')",session,customer);
        String forgedRole = mint(customer, Map.of("roles", List.of("ADMIN"),"jti",session.toString()), Set.of());
        assertError(as(forgedRole, "GET", "/users", null), 403, "FORBIDDEN");
        assertThat(as(forgedRole, "GET", "/me/accounts", null).statusCode()).isEqualTo(200);
    }

    @Test
    void corsAllowsOnlyConfiguredOriginsAndReturnsStructuredDenial() throws Exception {
        for (String origin : List.of("http://localhost:5173", "https://untrusted.example")) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Origin", origin).header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization").header("X-Request-Id", "transfer-it")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            if (origin.startsWith("http://localhost")) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
                assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
            } else assertError(response, 403, "FORBIDDEN");
        }
    }

    private UUID owner() { return jdbc.queryForObject("select customer_id from accounts where id=?", UUID.class, SOURCE); }
    private record Other(UUID customer, UUID account) {}
    private Other otherCustomer() {
        UUID customer = UUID.randomUUID(), account = UUID.randomUUID();
        jdbc.update("insert into customers(id,customer_code,full_name) values (?,?,?)", customer, "OTHER_" + customer.toString().substring(0,8), "Other Customer");
        jdbc.update("insert into accounts(id,customer_id,account_number) values (?,?,?)", account, customer, "OTHER_" + account.toString().substring(0,8));
        fixtures.allocate(account, new BigDecimal("500000.00"));
        return new Other(customer, account);
    }
    private HttpResponse<String> login(String username, String password) throws Exception {
        return as(null, "POST", "/auth/login", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }
    private HttpResponse<String> as(String authorization, String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .timeout(java.time.Duration.ofSeconds(12)).header("Content-Type", "application/json").header("X-Request-Id", "transfer-it");
        if (authorization != null) builder.header("Authorization", authorization);
        if (method.equals("POST") && path.equals("/transfers")) builder.header("Idempotency-Key",UUID.randomUUID().toString());
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
    private String mint(UUID id, Map<String, Object> changed, Set<String> removed) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("banking-local").subject(id.toString()).issuedAt(now)
            .notBefore(now).expiresAt(now.plusSeconds(900)).audience(List.of("banking-api"))
            .claims(values -> { values.putAll(changed); removed.forEach(values::remove); }).build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
