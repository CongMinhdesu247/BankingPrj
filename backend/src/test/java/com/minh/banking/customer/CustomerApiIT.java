package com.minh.banking.customer;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.config.import=", "spring.jpa.show-sql=false", "logging.level.org.hibernate.orm.jdbc.error=OFF"})
@org.springframework.context.annotation.Import(com.minh.banking.security.support.SecurityTestUsers.Config.class)
class CustomerApiIT {
    @Autowired com.minh.banking.security.support.SecurityTestUsers testUsers;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry properties) {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/banking_test")) {
            throw new IllegalStateException("Integration tests require a dedicated local TEST_DB_URL ending in /banking_test");
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
    void migrationBuildsSchemaAndCanRestartWithoutDdlChanges() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public'",
            Integer.class)).isEqualTo(21);
        assertThat(jdbc.queryForObject("select count(*) from roles", Integer.class)).isEqualTo(4);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class))
            .isEqualTo(4);
    }

    @Test
    void createsReadsUpdatesAndClearsCustomerContacts() throws Exception {
        var created = request("POST", "", customer("kh001", "ALICE@EXAMPLE.COM"));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(created.body());
        String path = "/" + body.get("id").asText();
        assertThat(created.headers().firstValue("Location")).contains("/api/v1/customers" + path);
        assertThat(body.get("email").asText()).isEqualTo("alice@example.com");
        assertThat(body.get("customerCode").asText()).isEqualTo("KH001");
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("createdAt").asText()).isNotBlank();
        assertThat(request("GET", path, null).statusCode()).isEqualTo(200);
        var patched = request("PATCH", path, "{\"phone\":\"+84912345678\"}");
        assertThat(patched.statusCode()).isEqualTo(200);
        assertThat(json.readTree(patched.body()).get("email").asText()).isEqualTo("alice@example.com");
        var cleared = request("PATCH", path, "{\"email\":\"\"}");
        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat(json.readTree(cleared.body()).get("email").isNull()).isTrue();
        assertThat(json.readTree(cleared.body()).get("phone").asText()).isEqualTo("+84912345678");
    }

    @Test
    void rejectsDuplicateIdentityAndRollsBackConflictingPatch() throws Exception {
        request("POST", "", customer("KH001", "alice@example.com"));
        assertError(request("POST", "", customer("KH001", "other@example.com")), 409, "CUSTOMER_CONFLICT");
        assertError(request("POST", "", customer("KH002", "ALICE@EXAMPLE.COM")), 409, "CUSTOMER_CONFLICT");
        var second = request("POST", "", customer("KH002", "bob@example.com"));
        String path = "/" + json.readTree(second.body()).get("id").asText();
        assertError(request("PATCH", path, "{\"email\":\"alice@example.com\"}"), 409, "CUSTOMER_CONFLICT");
        assertThat(json.readTree(request("GET", path, null).body()).get("email").asText()).isEqualTo("bob@example.com");
        assertThat(jdbc.queryForObject("select count(*) from customers", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentDuplicateRequestsCreateExactlyOneCustomer() throws Exception {
        var first = http.sendAsync(httpRequest("POST", "", customer("RACE", "race@example.com")),
            HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(httpRequest("POST", "", customer("RACE", "race@example.com")),
            HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(first, second).get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from customers", Integer.class)).isEqualTo(1);
    }

    @Test
    void paginatesWithStableTieBreakAndTreatsSearchWildcardsAsLiteral() throws Exception {
        request("POST", "", customer("C_01", "a@example.com"));
        request("POST", "", customer("CX01", "b@example.com"));
        request("POST", "", customer("CX02", "c@example.com"));
        jdbc.update("update customers set created_at = '2026-01-01T00:00:00Z'");
        Set<String> ids = new HashSet<>();
        List<String> ordered = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            var response = request("GET", "?page=" + page + "&size=1", null);
            assertThat(response.statusCode()).isEqualTo(200);
            var body = json.readTree(response.body());
            assertThat(body.get("totalElements").asInt()).isEqualTo(3);
            String id = body.get("content").get(0).get("id").asText();
            assertThat(ids.add(id)).isTrue();
            ordered.add(id);
        }
        var expected = jdbc.queryForList("select id::text from customers order by created_at,id", String.class);
        assertThat(ordered).isEqualTo(expected);
        var literal = json.readTree(request("GET", "?search=_", null).body());
        assertThat(literal.get("totalElements").asInt()).isEqualTo(1);
        assertThat(literal.get("content").get(0).get("customerCode").asText()).isEqualTo("C_01");
    }

    @Test
    void reportsValidationAndRoutingErrorsWithoutDatabaseDetails() throws Exception {
        assertError(request("POST", "", "{\"customerCode\":\"KH001\"}"), 400, "VALIDATION_ERROR");
        assertError(request("POST", "", customer("KH001", "invalid-email")), 400, "VALIDATION_ERROR");
        assertError(request("POST", "", "{broken"), 400, "INVALID_REQUEST");
        assertError(request("POST", "", "{\"customerCode\":\"KH001\",\"fullName\":\"A\",\"status\":\"INACTIVE\"}"),
            400, "INVALID_REQUEST");
        assertError(request("GET", "/not-a-uuid", null), 400, "INVALID_REQUEST");
        assertError(request("GET", "/" + UUID.randomUUID(), null), 404, "CUSTOMER_NOT_FOUND");
        assertError(request("GET", "?size=101", null), 400, "INVALID_REQUEST");
        assertError(request("GET", "?page=-1", null), 400, "INVALID_REQUEST");
        assertError(request("GET", "?size=0", null), 400, "INVALID_REQUEST");
        assertError(request("PATCH", "/" + UUID.randomUUID(), "{}"), 400, "EMPTY_UPDATE");
        assertError(request("DELETE", "/" + UUID.randomUUID(), null), 405, "METHOD_NOT_ALLOWED");
    }

    @Test
    void databaseRejectsUnnormalizedEmailAndUnknownAccountOwner() {
        assertThatThrownBy(() -> jdbc.update("insert into customers(customer_code,full_name,email) values ('BAD','A','UPPER@example.com')"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into accounts(customer_id,account_number) values (?, 'MISSING')", UUID.randomUUID()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    String customer(String code, String email) {
        return "{\"customerCode\":\"" + code + "\",\"fullName\":\"Nguyen Van A\",\"email\":\"" + email + "\"}";
    }

    HttpRequest httpRequest(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/customers" + path))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Content-Type", "application/json").header("X-Request-Id", "customer-it")
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
        assertThat(body.get("requestId").asText()).isEqualTo("customer-it");
        assertThat(response.headers().firstValue("X-Request-Id")).contains("customer-it");
        assertThat(body.get("path").asText()).startsWith("/api/v1/customers");
        assertThat(response.body()).doesNotContain("org.hibernate", "password_hash", "insert into", "stackTrace");
    }
}
