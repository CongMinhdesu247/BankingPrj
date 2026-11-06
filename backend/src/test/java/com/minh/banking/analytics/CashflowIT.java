package com.minh.banking.analytics;

import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class CashflowIT extends TransferIntegrationTestSupport {
    @Autowired Clock clock;
    private HttpResponse<String> get(String token, String query) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me/cashflow"+query)).header("X-Request-Id", "transfer-it").GET();
        if (token != null) builder.header("Authorization", token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void totalsIncludeTheWholeCustomerLedgerButNeverAnotherCustomer() throws Exception {
        var transfer = request("POST", "/transfers", payload(SOURCE, DESTINATION, "1000"));
        assertThat(transfer.statusCode()).as(transfer.body()).isEqualTo(201);
        var response = get(testUsers.customer(SOURCE), "?days=7");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var data = json.readTree(response.body());
        assertThat(data.path("series").size()).isEqualTo(7);
        assertThat(data.path("incoming").asText()).isEqualTo("1001000.00");
        assertThat(data.path("outgoing").asText()).isEqualTo("1000.00");
        assertThat(data.path("net").asText()).isEqualTo("1000000.00");
        var other = json.readTree(get(testUsers.customer(TREASURY), "?days=7").body());
        assertThat(other.path("incoming").asText()).isEqualTo("0");
        assertThat(other.path("outgoing").asText()).isEqualTo("1000000.00");
        assertThat(other.path("net").asText()).isEqualTo("-1000000.00");
    }
    @Test void newCustomerHasACompleteZeroSeries() throws Exception {
        var customer = java.util.UUID.randomUUID();
        var account = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id,customer_code,full_name) VALUES (?,?,?)", customer, "EMPTY_CHART_CUSTOMER", "Empty Chart Customer");
        jdbc.update("INSERT INTO accounts(id,customer_id,account_number) VALUES (?,?,?)", account, customer, "EMPTY-CHART-ACCOUNT");
        var data = json.readTree(get(testUsers.customer(account), "?days=30").body());
        assertThat(data.path("incoming").asText()).isEqualTo("0");
        assertThat(data.path("outgoing").asText()).isEqualTo("0");
        assertThat(data.path("series").size()).isEqualTo(30);
        for (var day:data.path("series")) {
            assertThat(day.path("incoming").asText()).isEqualTo("0");
            assertThat(day.path("outgoing").asText()).isEqualTo("0");
        }
    }
    @Test void requiresCustomerIdentityAndValidBoundedPeriods() throws Exception {
        assertError(get(null, ""), 401, "UNAUTHENTICATED");
        assertError(get(testUsers.staff(), ""), 403, "FORBIDDEN");
        for (String days:new String[]{"0", "6", "31", "1000"}) assertThat(get(testUsers.customer(SOURCE), "?days="+days).statusCode()).isEqualTo(400);
    }
}
