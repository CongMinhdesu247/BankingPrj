package com.minh.banking.account;

import jakarta.persistence.EntityManagerFactory;
import com.minh.banking.account.service.AccountNumberGenerator;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "spring.jpa.properties.hibernate.generate_statistics=true",
    "logging.level.org.hibernate.stat=OFF", "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@org.springframework.context.annotation.Import(com.minh.banking.security.support.SecurityTestUsers.Config.class)
class AccountApiIT {
    @Autowired com.minh.banking.security.support.SecurityTestUsers testUsers;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoSpyBean AccountNumberGenerator numbers;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry properties) {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/banking_test")) {
            throw new IllegalStateException("Account integration tests require a dedicated local banking_test database");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> requiredEnv("TEST_DB_USERNAME"));
        properties.add("spring.datasource.password", () -> requiredEnv("TEST_DB_PASSWORD"));
    }

    static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException("Missing " + name);
        return value;
    }

    @BeforeEach
    void cleanFixture() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("banking_test");
        jdbc.execute("TRUNCATE TABLE public.customers CASCADE");
    }

    @Test
    void createsMultipleAccountsWithSeparateNumbersAndReadsWithoutEntitySerialization() throws Exception {
        String owner = createCustomer("A");
        var first = createAccount(owner);
        var second = createAccount(owner);
        assertThat(first.get("customerId").asText()).isEqualTo(owner);
        assertThat(first.get("accountNumber").asText()).matches("10[0-9]{11}");
        assertThat(first.get("accountNumber").asText()).isNotEqualTo(second.get("accountNumber").asText());
        assertThat(first.get("balance").isTextual()).isTrue();
        assertThat(first.get("balance").asText()).isEqualTo("0.00");
        assertThat(first.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(first.has("customer")).isFalse();
        var detail = request("GET", "/accounts/" + first.get("id").asText(), null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(json.readTree(detail.body()).get("id")).isEqualTo(first.get("id"));
        assertThat(jdbc.queryForObject("select count(*) from customers", Integer.class)).isEqualTo(1);
    }

    @Test
    void listsOnlyRequestedOwnerWithStablePaginationAndNoNPlusOne() throws Exception {
        String owner = createCustomer("A");
        String other = createCustomer("B");
        createAccount(other);
        UUID ownerId = UUID.fromString(owner);
        jdbc.update("insert into accounts(customer_id,account_number,created_at) "
            + "select ?, 'FIXTURE' || n, '2026-01-01T00:00:00Z'::timestamptz from generate_series(1,12) n", ownerId);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var response = request("GET", "/customers/" + owner + "/accounts?page=0&size=10", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode page = json.readTree(response.body());
        assertThat(page.get("totalElements").asInt()).isEqualTo(12);
        assertThat(page.get("content").size()).isEqualTo(10);
        assertThat(statistics.getPrepareStatementCount()).isBetween(3L, 4L); // Includes current-user/role lookup.
        Set<String> ids = new HashSet<>();
        List<String> ordered = new ArrayList<>();
        for (JsonNode account : page.get("content")) {
            assertThat(account.get("customerId").asText()).isEqualTo(owner);
            ids.add(account.get("id").asText());
            ordered.add(account.get("id").asText());
        }
        var next = json.readTree(request("GET", "/customers/" + owner + "/accounts?page=1&size=10", null).body());
        for (JsonNode account : next.get("content")) {
            assertThat(ids.add(account.get("id").asText())).isTrue();
            ordered.add(account.get("id").asText());
        }
        assertThat(ordered).isEqualTo(jdbc.queryForList(
            "select id::text from accounts where customer_id=? order by created_at,id", String.class, ownerId));
    }

    @Test
    void rejectsMissingAndInactiveCustomersAndListsExistingCustomerWithNoAccounts() throws Exception {
        String missing = UUID.randomUUID().toString();
        assertError(request("POST", "/customers/" + missing + "/accounts", "{\"currency\":\"VND\"}"),
            404, "CUSTOMER_NOT_FOUND");
        assertError(request("GET", "/customers/" + missing + "/accounts", null), 404, "CUSTOMER_NOT_FOUND");
        String owner = createCustomer("A");
        var empty = request("GET", "/customers/" + owner + "/accounts", null);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(json.readTree(empty.body()).get("totalElements").asInt()).isZero();
        jdbc.update("update customers set status='INACTIVE' where id=?", UUID.fromString(owner));
        assertError(request("POST", "/customers/" + owner + "/accounts", "{\"currency\":\"VND\"}"),
            409, "CUSTOMER_NOT_ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from accounts", Integer.class)).isZero();
    }

    @Test
    void freezesUnfreezesClosesAndNeverReopensClosedAccount() throws Exception {
        String id = createAccount(createCustomer("A")).get("id").asText();
        for (String status : List.of("FROZEN", "ACTIVE", "CLOSED", "CLOSED")) {
            var response = status(id, status);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).get("status").asText()).isEqualTo(status);
        }
        assertError(status(id, "ACTIVE"), 409, "ACCOUNT_CLOSED");
        assertError(status(id, "FROZEN"), 409, "ACCOUNT_CLOSED");
        assertThat(jdbc.queryForObject("select version from accounts where id=?", Long.class, UUID.fromString(id)))
            .isEqualTo(3L); // Same-status request did not write again.
    }

    @Test
    void refusesToCloseFundedAccountAndKeepsBalanceExact() throws Exception {
        String id = createAccount(createCustomer("A")).get("id").asText();
        // Test-only fixture: no HTTP endpoint accepts direct balance updates.
        jdbc.update("update accounts set balance=99999999999999999 where id=?", UUID.fromString(id));
        assertError(status(id, "CLOSED"), 409, "ACCOUNT_BALANCE_NOT_ZERO");
        var detail = json.readTree(request("GET", "/accounts/" + id, null).body());
        assertThat(detail.get("balance").asText()).isEqualTo("99999999999999999.00");
        assertThat(detail.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void refusesClientChosenBalanceNumberCurrencyAndInvalidStatuses() throws Exception {
        String owner = createCustomer("A");
        String createPath = "/customers/" + owner + "/accounts";
        assertError(request("POST", createPath, "{\"currency\":\"VND\",\"balance\":\"100\"}"), 400, "INVALID_REQUEST");
        assertError(request("POST", createPath, "{\"currency\":\"VND\",\"accountNumber\":\"123\"}"), 400, "INVALID_REQUEST");
        assertError(request("POST", createPath, "{\"currency\":\"USD\"}"), 400, "VALIDATION_ERROR");
        assertError(request("POST", createPath, "{}"), 400, "VALIDATION_ERROR");
        String id = createAccount(owner).get("id").asText();
        assertError(status(id, "UNKNOWN"), 400, "INVALID_REQUEST");
        assertError(request("PATCH", "/accounts/" + id + "/status", "{}"), 400, "VALIDATION_ERROR");
        assertError(request("GET", createPath + "?size=101", null), 400, "INVALID_REQUEST");
        assertError(request("GET", "/accounts/not-a-uuid", null), 400, "INVALID_REQUEST");
        assertError(request("GET", "/accounts/" + UUID.randomUUID(), null), 404, "ACCOUNT_NOT_FOUND");
        assertError(status(UUID.randomUUID().toString(), "FROZEN"), 404, "ACCOUNT_NOT_FOUND");
    }

    @Test
    void statusChangeActuallyWaitsForPostgresRowLock() throws Exception {
        String id = createAccount(createCustomer("A")).get("id").asText();
        java.util.concurrent.CompletableFuture<HttpResponse<String>> pending;
        try (var holder = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (var lock = holder.prepareStatement("select id from accounts where id=? for update")) {
                    lock.setObject(1, UUID.fromString(id));
                    lock.executeQuery().close();
                }
                pending = http.sendAsync(httpRequest("PATCH", "/accounts/" + id + "/status", "{\"status\":\"FROZEN\"}"),
                    HttpResponse.BodyHandlers.ofString());
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from pg_stat_activity where datname=current_database() "
                        + "and wait_event_type='Lock' and query like '%accounts%')", Boolean.class));
                    if (waiting) break;
                    Thread.sleep(25);
                }
                assertThat(waiting).as("HTTP status update must wait for the held account row lock").isTrue();
                assertThat(pending.isDone()).isFalse();
            } finally {
                holder.rollback(); // Always release the fixture lock, including on assertion failure.
            }
        }
        assertThat(pending.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from accounts where id=?", String.class, UUID.fromString(id)))
            .isEqualTo("FROZEN");
    }

    @Test
    void generatedNumberCollisionReturnsControlledConflictAndRollsBack() throws Exception {
        org.mockito.Mockito.doReturn("1000000000001").when(numbers).next();
        String owner = createCustomer("A");
        createAccount(owner);
        assertError(request("POST", "/customers/" + owner + "/accounts", "{\"currency\":\"VND\"}"),
            409, "ACCOUNT_NUMBER_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from accounts", Integer.class)).isEqualTo(1);
    }

    @Test
    void databaseConstraintsProtectBalanceClosureAndCustomerHistory() throws Exception {
        String owner = createCustomer("A");
        UUID id = UUID.fromString(createAccount(owner).get("id").asText());
        assertThatThrownBy(() -> jdbc.update("update accounts set balance=-1 where id=?", id))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("update accounts set balance=100 where id=?", id);
        assertThatThrownBy(() -> jdbc.update("update accounts set status='CLOSED' where id=?", id))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from customers where id=?", UUID.fromString(owner)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select status from accounts where id=?", String.class, id)).isEqualTo("ACTIVE");
    }

    @Test
    void concurrentCloseAndFreezeNeverReviveClosedAccount() throws Exception {
        String id = createAccount(createCustomer("A")).get("id").asText();
        var close = http.sendAsync(httpRequest("PATCH", "/accounts/" + id + "/status", "{\"status\":\"CLOSED\"}"),
            HttpResponse.BodyHandlers.ofString());
        var freeze = http.sendAsync(httpRequest("PATCH", "/accounts/" + id + "/status", "{\"status\":\"FROZEN\"}"),
            HttpResponse.BodyHandlers.ofString());
        assertThat(close.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(freeze.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isIn(200, 409);
        assertThat(jdbc.queryForObject("select status from accounts where id=?", String.class, UUID.fromString(id)))
            .isEqualTo("CLOSED");
    }

    String createCustomer(String code) throws Exception {
        var response = request("POST", "/customers", "{\"customerCode\":\"" + code + "\",\"fullName\":\"Test Customer\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asText();
    }

    JsonNode createAccount(String owner) throws Exception {
        var response = request("POST", "/customers/" + owner + "/accounts", "{\"currency\":\"VND\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var result = json.readTree(response.body());
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/accounts/" + result.get("id").asText());
        return result;
    }

    HttpResponse<String> status(String id, String status) throws Exception {
        return request("PATCH", "/accounts/" + id + "/status", "{\"status\":\"" + status + "\"}");
    }

    HttpRequest httpRequest(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
            .header("X-Request-Id", "account-it")
            .header("Authorization", testUsers.staff())
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    HttpResponse<String> request(String method, String path, String body) throws Exception {
        return http.send(httpRequest(method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("requestId").asText()).isEqualTo("account-it");
        assertThat(response.body()).doesNotContain("org.hibernate", "insert into", "stackTrace");
    }
}
