package com.minh.banking.auth;

import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

class RegistrationIT extends TransferIntegrationTestSupport {
    @Autowired PasswordEncoder passwords;
    private Map<String, Object> input(String username, String email) {
        var body = new HashMap<String, Object>();
        body.put("fullName", "Nguyễn Khách Mới");
        body.put("email", email);
        body.put("username", username);
        body.put("password", "NewCustomerPassword123!");
        return body;
    }
    private HttpResponse<String> post(String path, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path))
            .header("Content-Type", "application/json").header("X-Request-Id", "transfer-it")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void anonymousCustomerCanRegisterLoginAndOnlySeeTheirZeroBalanceAccount() throws Exception {
        var created = post("/auth/register", input("  New.Customer  ", "  New@Example.com  "));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        var result = json.readTree(created.body());
        assertThat(result.path("username").asText()).isEqualTo("new.customer");
        var user = jdbc.queryForMap("SELECT id,customer_id,password_hash FROM users WHERE username=?", "new.customer");
        assertThat(passwords.matches("NewCustomerPassword123!", (String)user.get("password_hash"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id=?", String.class, user.get("customer_id"))).isEqualTo("new@example.com");
        assertThat(jdbc.queryForList("SELECT r.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=?", String.class, user.get("id"))).containsExactly("CUSTOMER");
        var login = post("/auth/login", Map.of("username", "NEW.CUSTOMER", "password", "NewCustomerPassword123!"));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String token = json.readTree(login.body()).path("accessToken").asText();
        var owned = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/accounts"))
            .header("Authorization", "Bearer "+token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(owned.statusCode()).as(owned.body()).isEqualTo(200);
        var accounts = json.readTree(owned.body()).path("content");
        assertThat(accounts.size()).isEqualTo(1);
        assertThat(accounts.path(0).path("balance").asText()).isIn("0", "0.00");
        assertThat(accounts.path(0).path("accountNumber").asText()).isEqualTo(result.path("accountNumber").asText());
        var staff = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/staff/customers"))
            .header("Authorization", "Bearer "+token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(staff.statusCode()).isEqualTo(403);
        assertThat(created.body()).doesNotContain("password", "hash", "accessToken");
    }
    @Test void duplicateUsernameOrEmailRollsBackTheEntireRegistration() throws Exception {
        assertThat(post("/auth/register", input("registered", "registered@example.com")).statusCode()).isEqualTo(201);
        var before = jdbc.queryForMap("SELECT (SELECT count(*) FROM customers) customers,(SELECT count(*) FROM users) users,(SELECT count(*) FROM accounts) accounts,(SELECT count(*) FROM audit_logs) audit");
        assertError(post("/auth/register", input("REGISTERED", "different@example.com")), 409, "REGISTRATION_CONFLICT");
        assertError(post("/auth/register", input("different", "REGISTERED@example.com")), 409, "REGISTRATION_CONFLICT");
        assertThat(jdbc.queryForMap("SELECT (SELECT count(*) FROM customers) customers,(SELECT count(*) FROM users) users,(SELECT count(*) FROM accounts) accounts,(SELECT count(*) FROM audit_logs) audit")).isEqualTo(before);
    }
    @Test void cannotChooseRolesBalanceOrCustomerLinkAndInvalidDataCreatesNothing() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM customers", Long.class);
        for (String privileged:List.of("roles", "balance", "customerId", "enabled")) {
            var body = input("rejected", "rejected@example.com");
            body.put(privileged, privileged.equals("roles")?List.of("ADMIN"):"forbidden");
            assertThat(post("/auth/register", body).statusCode()).isEqualTo(400);
        }
        var bad = input("a!", "not-email");
        bad.put("password", "short");
        assertThat(post("/auth/register", bad).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Long.class)).isEqualTo(before);
    }
    @Test void concurrentIdenticalRegistrationsCreateOneCompleteAccount() throws Exception {
        try (var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var latch = new java.util.concurrent.CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i=0; i<2; i++) tasks.add(pool.submit(() -> {latch.await(); return post("/auth/register", input("concurrent", "concurrent@example.com")).statusCode(); }));
            latch.countDown();
            assertThat(List.of(tasks.get(0).get(), tasks.get(1).get())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username='concurrent'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts a JOIN users u ON u.customer_id=a.customer_id WHERE u.username='concurrent'", Integer.class)).isEqualTo(1);
    }
}
