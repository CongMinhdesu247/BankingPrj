package com.minh.banking.phase6;

import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class IdempotencyIT extends PhaseSixSupport {
    @Test void missingAndInvalidKeyNeverChangeMoney() throws Exception {
        var before=accountSnapshot();
        for (String key:new String[]{null,"contains space","x".repeat(129)})
            assertError(transfer(key,"1000"),400,"INVALID_IDEMPOTENCY_KEY");
        assertThat(count("idempotency_keys")).isZero();
        assertThat(accountSnapshot()).isEqualTo(before);
        assertLedgerConsistent(0);
    }
    @Test void retryAfterCommittedButDiscardedResponseReturnsCanonicalOriginalSnapshot() throws Exception {
        var first=transfer("lost-response","1000");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(first.headers().firstValue("Idempotency-Replayed")).contains("false");
        assertThat(transfer("another-transfer","2000").statusCode()).isEqualTo(201);
        String reordered="{\"description\":\"Demo transfer\",\"currency\":\"VND\",\"amount\":\"1000.00\",\"destinationAccountId\":\""+DESTINATION+"\",\"sourceAccountId\":\""+SOURCE+"\"}";
        var retry=as(testUsers.customer(SOURCE),"POST","/transfers",reordered,"lost-response");
        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(201);
        assertThat(json.readTree(retry.body())).isEqualTo(json.readTree(first.body()));
        assertThat(retry.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
        assertThat(retry.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(actions("TRANSFER_COMPLETED")).isEqualTo(2);
        assertThat(count("idempotency_keys")).isEqualTo(2);
        assertBalances("997000","3000"); assertLedgerConsistent(2);
    }
    @Test void sameKeyDifferentPayloadConflictsWithoutAnotherDebit() throws Exception {
        assertThat(transfer("conflict","1000").statusCode()).isEqualTo(201);
        var before=accountSnapshot();
        assertError(transfer("conflict","2000"),409,"IDEMPOTENCY_CONFLICT");
        assertThat(accountSnapshot()).isEqualTo(before); assertLedgerConsistent(1);
    }
    @Test void fourConcurrentDuplicatesCommitExactlyOneTransactionAndOneSuccessAudit() throws Exception {
        String token=testUsers.customer(SOURCE),body=payload(SOURCE,DESTINATION,"1000");
        var responses=together(asRequest(token,"POST","/transfers",body,"concurrent"),
            asRequest(token,"POST","/transfers",body,"concurrent"),asRequest(token,"POST","/transfers",body,"concurrent"),
            asRequest(token,"POST","/transfers",body,"concurrent"));
        assertThat(responses).allSatisfy(response->assertThat(response.statusCode()).as(response.body()).isEqualTo(201));
        assertThat(responses.stream().map(r->json.readTree(r.body())).distinct()).hasSize(1);
        assertThat(responses.stream().filter(r->r.headers().firstValue("Idempotency-Replayed").orElseThrow().equals("false"))).hasSize(1);
        assertThat(count("idempotency_keys")).isEqualTo(1); assertThat(actions("TRANSFER_COMPLETED")).isEqualTo(1);
        assertBalances("999000","1000"); assertLedgerConsistent(1);
    }
    @Test void concurrentDifferentPayloadOnOneKeyHasOneWinnerAndOneConflict() throws Exception {
        String token=testUsers.customer(SOURCE);
        var responses=together(asRequest(token,"POST","/transfers",payload(SOURCE,DESTINATION,"1000"),"race"),
            asRequest(token,"POST","/transfers",payload(SOURCE,DESTINATION,"2000"),"race"));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201,409);
        assertError(responses.stream().filter(r->r.statusCode()==409).findFirst().orElseThrow(),409,"IDEMPOTENCY_CONFLICT");
        assertLedgerConsistent(1);
    }
    @Test void businessRollbackRemovesClaimAndDurableFailureAuditSurvives() throws Exception {
        var before=accountSnapshot();
        assertError(transfer("retry-failed","2000000"),409,"INSUFFICIENT_FUNDS");
        assertThat(count("idempotency_keys")).isZero(); assertThat(accountSnapshot()).isEqualTo(before);
        assertThat(actions("TRANSFER_FAILED")).isEqualTo(1);
        assertThat(transfer("retry-failed","1000").statusCode()).isEqualTo(201);
        assertLedgerConsistent(1);
    }
    @Test void actorScopeAndOwnershipAreCheckedBeforeReplayAndCounterpartyBalanceStaysHidden() throws Exception {
        String body=payload(SOURCE,TREASURY,"1000");
        var first=as(testUsers.customer(SOURCE),"POST","/transfers",body,"actor-key");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(json.readTree(first.body()).get("entries").get(1).get("balanceAfter").isNull()).isTrue();
        assertError(as(testUsers.customer(TREASURY),"POST","/transfers",body,"actor-key"),404,"ACCOUNT_NOT_FOUND");
        var other=as(testUsers.customer(TREASURY),"POST","/transfers",payload(TREASURY,SOURCE,"1000"),"actor-key");
        assertThat(other.statusCode()).as(other.body()).isEqualTo(201);
        var retry=as(testUsers.customer(SOURCE),"POST","/transfers",body,"actor-key");
        assertThat(json.readTree(retry.body())).isEqualTo(json.readTree(first.body()));
        assertThat(count("idempotency_keys")).isEqualTo(2); assertLedgerConsistent(2);
    }
    @Test void expiredKeyCanStartNewTransferWhileOldTransactionAndAuditRemain() throws Exception {
        var first=transfer("expired","1000"); assertThat(first.statusCode()).isEqualTo(201);
        jdbc.update("UPDATE idempotency_keys SET created_at=now()-interval '8 days',expires_at=now()-interval '1 day'");
        var second=transfer("expired","2000"); assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        assertThat(json.readTree(second.body()).get("transaction").get("id")).isNotEqualTo(json.readTree(first.body()).get("transaction").get("id"));
        assertThat(count("idempotency_keys")).isEqualTo(1); assertThat(actions("TRANSFER_COMPLETED")).isEqualTo(2);
        assertLedgerConsistent(2);
    }
    @Test void uncommittedClaimTimeoutIsRetryableAndDoesNotQueryAnAbortedTransaction() throws Exception {
        assertThat(transfer("held-key","1000").statusCode()).isEqualTo(201);
        String token=testUsers.customer(SOURCE);
        try (var holder=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (var lock=holder.prepareStatement("SELECT id FROM idempotency_keys WHERE key='held-key' FOR UPDATE")) { lock.executeQuery().close(); }
                var response=as(token,"POST","/transfers",payload(SOURCE,DESTINATION,"1000"),"held-key");
                assertError(response,503,"ACCOUNT_BUSY");
                assertThat(response.headers().firstValue("Retry-After")).contains("1");
            } finally { holder.rollback(); }
        }
        var retry=transfer("held-key","1000"); assertThat(retry.statusCode()).as(retry.body()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertLedgerConsistent(1);
    }
}
