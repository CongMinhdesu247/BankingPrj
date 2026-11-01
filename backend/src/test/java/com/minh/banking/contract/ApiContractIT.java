package com.minh.banking.contract;

import com.minh.banking.customer.service.CustomerService;
import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class ApiContractIT extends TransferIntegrationTestSupport {
    @MockitoSpyBean CustomerService customers;

    @Test
    void openApiMatchesRoutesSchemasAndCreatedResponses() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var api = json.readTree(response.body());
        assertThat(api.get("paths").has("/api/v1/transfers")).isTrue();
        assertThat(api.get("paths").has("/api/v1/accounts/{id}/transactions")).isTrue();
        assertThat(api.get("paths").get("/api/v1/transfers").get("post").get("responses").has("201")).isTrue();
        var error = api.get("components").get("schemas").get("ApiError").get("properties");
        for (String name : List.of("code", "message", "fieldErrors", "requestId", "timestamp", "path")) {
            assertThat(error.has(name)).as(name).isTrue();
        }
        var amount = api.get("components").get("schemas").get("TransferRequest").get("properties").get("amount");
        assertThat(amount.toString()).contains("string", "200000");
        var ui = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/swagger-ui/index.html")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("Swagger UI");
    }

    @Test
    void parameterErrorsIncludeFieldNameAndUniformErrorShape() throws Exception {
        var response = request("GET", "/customers?size=101", null);
        assertError(response, 400, "INVALID_REQUEST");
        var body = json.readTree(response.body());
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("size");
        var wrongId = request("GET", "/accounts/not-a-uuid", null);
        assertError(wrongId, 400, "INVALID_REQUEST");
        assertThat(json.readTree(wrongId.body()).get("fieldErrors").get(0).get("field").asText()).isEqualTo("id");
        var wrongDate = request("GET", "/accounts/" + SOURCE + "/transactions?from=not-a-date", null);
        assertError(wrongDate, 400, "INVALID_REQUEST");
        assertThat(json.readTree(wrongDate.body()).get("fieldErrors").get(0).get("field").asText()).isEqualTo("from");
        assertThat(body.get("timestamp").asText()).isNotBlank();
        assertThat(body.get("path").asText()).isEqualTo("/api/v1/customers");
    }

    @Test
    void frameworkErrorsAndBodyValidationUseTheSameContract() throws Exception {
        assertError(request("GET", "/not-a-route", null), 404, "RESOURCE_NOT_FOUND");
        assertError(request("DELETE", "/accounts/" + SOURCE, null), 405, "METHOD_NOT_ALLOWED");
        var malformed = request("POST", "/transfers", "{broken");
        assertError(malformed, 400, "INVALID_REQUEST");
        var invalid = request("POST", "/customers", "{}");
        assertError(invalid, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(invalid.body()).get("fieldErrors").size()).isGreaterThan(0);
        var unsupported = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/customers"))
            .header("X-Request-Id", "transfer-it").header("Content-Type", "text/plain")
            .header("Authorization", testUsers.staff())
            .POST(HttpRequest.BodyPublishers.ofString("hello")).build(), HttpResponse.BodyHandlers.ofString());
        assertError(unsupported, 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unexpectedFailureHasRequestIdAndNeverExposesInternalException() throws Exception {
        doThrow(new IllegalStateException("secret SQL insert into customer password=never-expose"))
            .when(AopTestUtils.<CustomerService>getUltimateTargetObject(customers)).get(any(UUID.class));
        var response = request("GET", "/customers/" + UUID.randomUUID(), null);
        assertError(response, 500, "INTERNAL_ERROR");
        assertThat(response.body()).doesNotContain("secret", "IllegalStateException", "never-expose");
        assertThat(response.headers().firstValue("X-Request-Id")).contains("transfer-it");
    }

    @Test
    void missingOrInvalidRequestIdGetsSafeGeneratedCorrelationId() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/accounts/not-a-uuid"))
            .header("X-Request-Id", "invalid spaces").header("Authorization", testUsers.staff()).GET().build(), HttpResponse.BodyHandlers.ofString());
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(json.readTree(response.body()).get("requestId").asText()).isEqualTo(requestId);
    }
}
