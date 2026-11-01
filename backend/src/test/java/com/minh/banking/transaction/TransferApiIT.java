package com.minh.banking.transaction;

import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class TransferApiIT extends TransferIntegrationTestSupport {
    @Test
    void transfersAtomicallyAndReturnsMatchingDebitCreditSnapshots() throws Exception {
        var response = request("POST", "/transfers", payload(SOURCE, DESTINATION, "200000"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var detail = json.readTree(response.body());
        var transaction = detail.get("transaction");
        assertThat(transaction.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(transaction.get("type").asText()).isEqualTo("INTERNAL_TRANSFER");
        assertThat(transaction.get("amount").asText()).isEqualTo("200000.00");
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/transactions/" + transaction.get("id").asText());
        assertThat(detail.get("entries").size()).isEqualTo(2);
        var debit = detail.get("entries").get(0);
        var credit = detail.get("entries").get(1);
        assertThat(debit.get("direction").asText()).isEqualTo("DEBIT");
        assertThat(debit.get("accountId").asText()).isEqualTo(SOURCE.toString());
        assertThat(debit.get("balanceAfter").asText()).isEqualTo("800000.00");
        assertThat(credit.get("direction").asText()).isEqualTo("CREDIT");
        assertThat(credit.get("accountId").asText()).isEqualTo(DESTINATION.toString());
        assertThat(credit.get("balanceAfter").asText()).isEqualTo("200000.00");
        var read = request("GET", "/transactions/" + transaction.get("id").asText(), null);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(json.readTree(read.body())).isEqualTo(detail);
        assertBalances("800000", "200000");
        assertLedgerConsistent(1);
    }

    @Test
    void rejectsInsufficientFundsSelfTransferMissingAccountsAndFrozenOrClosedAccounts() throws Exception {
        var snapshot = accountSnapshot();
        assertError(request("POST", "/transfers", payload(SOURCE, DESTINATION, "1000001")), 409, "INSUFFICIENT_FUNDS");
        assertError(request("POST", "/transfers", payload(SOURCE, SOURCE, "1")), 400, "SAME_ACCOUNT");
        assertError(request("POST", "/transfers", payload(SOURCE, UUID.randomUUID(), "1")), 404, "ACCOUNT_NOT_FOUND");
        assertThat(accountSnapshot()).isEqualTo(snapshot);
        for (UUID id : List.of(SOURCE, DESTINATION)) {
            assertThat(request("PATCH", "/accounts/" + id + "/status", "{\"status\":\"FROZEN\"}").statusCode()).isEqualTo(200);
            assertError(request("POST", "/transfers", payload(SOURCE, DESTINATION, "1")), 409, "ACCOUNT_NOT_ACTIVE");
            request("PATCH", "/accounts/" + id + "/status", "{\"status\":\"ACTIVE\"}");
        }
        request("PATCH", "/accounts/" + DESTINATION + "/status", "{\"status\":\"CLOSED\"}");
        assertError(request("POST", "/transfers", payload(SOURCE, DESTINATION, "1")), 409, "ACCOUNT_NOT_ACTIVE");
        assertBalances("1000000", "0");
        assertLedgerConsistent(0);
    }

    @Test
    void rejectsBadAmountsCurrencyAndUnexpectedFieldsWithoutRoundingOrWriting() throws Exception {
        var snapshot = accountSnapshot();
        assertError(request("POST", "/transfers", payload(SOURCE, DESTINATION, "0")), 400, "INVALID_AMOUNT");
        for (String amount : List.of("-1", "1.1", "1.000", "0.01", "1e3", "100000000000000000", "", " 1")) {
            assertError(request("POST", "/transfers", payload(SOURCE, DESTINATION, amount)), 400, "VALIDATION_ERROR");
        }
        assertError(request("POST", "/transfers", payload(SOURCE, DESTINATION, "1").replace("VND", "USD")),
            400, "VALIDATION_ERROR");
        assertError(request("POST", "/transfers", "{}"), 400, "VALIDATION_ERROR");
        assertError(request("POST", "/transfers", payload(SOURCE, DESTINATION, "1").replace("\"amount\":\"1\"", "\"amount\":1")),
            400, "INVALID_REQUEST");
        assertError(request("POST", "/transfers", payload(SOURCE, DESTINATION, "1").replace("Demo transfer", "x".repeat(256))),
            400, "VALIDATION_ERROR");
        assertThat(accountSnapshot()).isEqualTo(snapshot);
        assertLedgerConsistent(0);
    }

    @Test
    void historyIncludesBothDirectionsUsesHalfOpenTimeRangeAndStablePagination() throws Exception {
        var sent = json.readTree(request("POST", "/transfers", payload(SOURCE, DESTINATION, "200000.00")).body()).get("transaction");
        String id = sent.get("id").asText();
        String time = sent.get("createdAt").asText();
        var source = json.readTree(request("GET", "/accounts/" + SOURCE + "/transactions?size=1", null).body());
        assertThat(source.get("totalElements").asInt()).isEqualTo(2); // OPENING + transfer.
        assertThat(source.get("content").get(0).get("id").asText()).isEqualTo(id);
        var next = json.readTree(request("GET", "/accounts/" + SOURCE + "/transactions?page=1&size=1", null).body());
        assertThat(next.get("content").get(0).get("type").asText()).isEqualTo("OPENING");
        var destination = json.readTree(request("GET", "/accounts/" + DESTINATION + "/transactions?from=" + time, null).body());
        assertThat(destination.get("totalElements").asInt()).isEqualTo(1);
        assertThat(json.readTree(request("GET", "/accounts/" + DESTINATION + "/transactions?to=" + time, null).body())
            .get("totalElements").asInt()).isZero();
        assertError(request("GET", "/accounts/" + SOURCE + "/transactions?from=" + time + "&to=" + time, null),
            400, "INVALID_TIME_RANGE");
        assertError(request("GET", "/accounts/" + SOURCE + "/transactions?size=101", null), 400, "INVALID_REQUEST");
        assertError(request("GET", "/accounts/" + UUID.randomUUID() + "/transactions", null), 404, "ACCOUNT_NOT_FOUND");
        assertError(request("GET", "/transactions/" + UUID.randomUUID(), null), 404, "TRANSACTION_NOT_FOUND");
        assertError(request("PATCH", "/transactions/" + id, "{}"), 405, "METHOD_NOT_ALLOWED");
        assertError(request("DELETE", "/transactions/" + id, null), 405, "METHOD_NOT_ALLOWED");
        assertLedgerConsistent(1);
    }
}
