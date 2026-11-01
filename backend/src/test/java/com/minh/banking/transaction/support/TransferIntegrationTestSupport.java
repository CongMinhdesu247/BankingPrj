package com.minh.banking.transaction.support;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.config.import=", "banking.transfer.lock-timeout-ms=700"
})
@Import({BankingTestFixtures.class, com.minh.banking.security.support.SecurityTestUsers.Config.class})
public abstract class TransferIntegrationTestSupport {
    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected BankingTestFixtures.FixtureWriter fixtures;
    @Autowired protected com.minh.banking.security.support.SecurityTestUsers testUsers;
    protected final HttpClient http = HttpClient.newHttpClient();
    protected final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry properties) {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/banking_test")) {
            throw new IllegalStateException("Transfer tests require a dedicated local banking_test database");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> requiredEnv("TEST_DB_USERNAME"));
        properties.add("spring.datasource.password", () -> requiredEnv("TEST_DB_PASSWORD"));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException("Missing " + name);
        return value;
    }

    @BeforeEach
    protected void resetFixture() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("banking_test");
        jdbc.execute("TRUNCATE TABLE public.customers CASCADE");
        jdbc.execute("TRUNCATE TABLE public.outbox_events, public.processed_events");
        fixtures.seed();
    }

    protected String payload(UUID source, UUID destination, String amount) {
        return "{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\"" + destination
            + "\",\"amount\":\"" + amount + "\",\"currency\":\"VND\",\"description\":\"Demo transfer\"}";
    }

    protected HttpRequest httpRequest(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(12)).header("Content-Type", "application/json")
            .header("X-Request-Id", "transfer-it")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Authorization", authorizationFor(path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    protected String authorizationFor(String path) {
        return path.startsWith("/customers") || path.endsWith("/status")
            ? testUsers.staff() : testUsers.customer(SOURCE);
    }

    protected HttpResponse<String> request(String method, String path, String body) throws Exception {
        return http.send(httpRequest(method, path, body), HttpResponse.BodyHandlers.ofString());
    }

    protected void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("requestId").asText()).isEqualTo("transfer-it");
        assertThat(response.body()).doesNotContain("org.hibernate", "insert into", "stackTrace");
    }

    protected void assertBalances(String source, String destination) {
        assertThat(jdbc.queryForObject("select balance from accounts where id=?", java.math.BigDecimal.class, SOURCE))
            .isEqualByComparingTo(source);
        assertThat(jdbc.queryForObject("select balance from accounts where id=?", java.math.BigDecimal.class, DESTINATION))
            .isEqualByComparingTo(destination);
    }

    protected List<Map<String, Object>> accountSnapshot() {
        return jdbc.queryForList("select id,balance,status,version,updated_at from accounts order by id");
    }

    protected void assertLedgerConsistent(int completedTransfers) {
        assertThat(jdbc.queryForObject("select count(*) from transactions where type='INTERNAL_TRANSFER'", Integer.class))
            .isEqualTo(completedTransfers);
        assertThat(jdbc.queryForObject("""
            select count(*) from (
                select t.id from transactions t left join transaction_entries e on e.transaction_id=t.id
                group by t.id having count(e.id)<>2 or count(distinct e.direction)<>2
                  or sum(case when e.direction='DEBIT' then -e.amount else e.amount end)<>0
                  or bool_or(e.amount<>t.amount or e.currency<>t.currency
                    or (e.direction='DEBIT' and e.account_id<>t.source_account_id)
                    or (e.direction='CREDIT' and e.account_id<>t.destination_account_id))
            ) violations
            """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
            select count(*) from accounts a where a.balance < 0 or a.balance <>
              (case when a.id=? then 10000000 else 0 end + coalesce(
                (select sum(case when e.direction='CREDIT' then e.amount else -e.amount end)
                 from transaction_entries e where e.account_id=a.id),0))
            """, Integer.class, TREASURY)).isZero();
        assertThat(jdbc.queryForObject("select sum(balance) from accounts", java.math.BigDecimal.class))
            .isEqualByComparingTo(RESERVE);
    }
}
