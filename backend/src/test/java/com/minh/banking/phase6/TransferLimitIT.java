package com.minh.banking.phase6;

import java.net.http.HttpResponse;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.minh.banking.user.enums.RoleCode;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class TransferLimitIT extends PhaseSixSupport {
    @MockitoBean Clock clock;
    @BeforeEach void time() { when(clock.instant()).thenReturn(Instant.now()); }
    @Test void demoDefaultsAndOwnershipAreExplicit() throws Exception {
        var response=as(testUsers.customer(SOURCE),"GET","/me/transfer-limits?accountId="+SOURCE,null,null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var body=json.readTree(response.body());
        assertThat(body.get("perTransferAmount").asText()).isEqualTo("100000000.00");
        assertThat(body.get("dailyAmount").asText()).isEqualTo("500000000.00");
        assertThat(body.get("dailyUsed").asText()).isEqualTo("0.00"); // OPENING is excluded.
        assertThat(body.get("defaultApplied").asBoolean()).isTrue();
        assertThat(body.get("timezone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
        assertError(as(testUsers.customer(TREASURY),"GET","/me/transfer-limits?accountId="+SOURCE,null,null),404,"ACCOUNT_NOT_FOUND");
    }
    @Test void exactPerTransferAndDailyBoundariesAndRejectionsPreserveMoney() throws Exception {
        assertThat(limit("400000","600000").statusCode()).isEqualTo(200);
        var before=accountSnapshot();
        assertError(transfer("per-over","400001"),409,"PER_TRANSFER_LIMIT_EXCEEDED");
        assertThat(accountSnapshot()).isEqualTo(before);
        assertThat(transfer("per-exact","400000").statusCode()).isEqualTo(201);
        assertThat(transfer("daily-exact","200000").statusCode()).isEqualTo(201);
        var after=accountSnapshot();
        assertError(transfer("daily-over","1"),409,"DAILY_LIMIT_EXCEEDED");
        assertThat(accountSnapshot()).isEqualTo(after);
        assertThat(transfer("per-exact","400000").headers().firstValue("Idempotency-Replayed")).contains("true");
        var info=as(testUsers.customer(SOURCE),"GET","/me/transfer-limits?accountId="+SOURCE,null,null);
        assertThat(json.readTree(info.body()).get("dailyUsed").asText()).isEqualTo("600000.00");
        assertThat(count("idempotency_keys")).isEqualTo(2);
        assertBalances("400000","600000"); assertLedgerConsistent(2);
    }
    @Test void concurrentTransfersToDifferentDestinationsCannotExceedSourceDailyQuota() throws Exception {
        assertThat(limit("400000","600000").statusCode()).isEqualTo(200);
        String token=testUsers.customer(SOURCE);
        var responses=together(asRequest(token,"POST","/transfers",payload(SOURCE,DESTINATION,"400000"),"quota-a"),
            asRequest(token,"POST","/transfers",payload(SOURCE,TREASURY,"400000"),"quota-b"));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201,409);
        assertError(responses.stream().filter(r->r.statusCode()==409).findFirst().orElseThrow(),409,"DAILY_LIMIT_EXCEEDED");
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?",java.math.BigDecimal.class,SOURCE)).isEqualByComparingTo("600000");
        assertThat(actions("TRANSFER_COMPLETED")).isEqualTo(1); assertLedgerConsistent(1);
    }
    @Test void dayChangesAtVietnamMidnightAndTransactionUsesSamePostLockInstant() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-27T16:59:59.999999Z"));
        assertThat(limit("1000","1000").statusCode()).isEqualTo(200);
        var first=transfer("before-midnight","1000"); assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertError(transfer("before-midnight-over","1"),409,"DAILY_LIMIT_EXCEEDED");
        when(clock.instant()).thenReturn(Instant.parse("2026-10-27T17:00:00Z"));
        var second=transfer("after-midnight","1000"); assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        var info=json.readTree(as(testUsers.customer(SOURCE),"GET","/me/transfer-limits?accountId="+SOURCE,null,null).body());
        assertThat(info.get("date").asText()).isEqualTo("2026-10-28");
        assertThat(info.get("dailyUsed").asText()).isEqualTo("1000.00");
        assertThat(json.readTree(first.body()).get("transaction").get("createdAt").asText()).isEqualTo("2026-10-27T16:59:59.999999Z");
        assertThat(json.readTree(second.body()).get("transaction").get("createdAt").asText()).isEqualTo("2026-10-27T17:00:00Z");
        assertLedgerConsistent(2);
    }
    @Test void onlyAdminCanSetValidWholeVndLimits() throws Exception {
        String path="/accounts/"+SOURCE+"/transfer-limit",body="{\"perTransferAmount\":\"100\",\"dailyAmount\":\"1000\"}";
        assertError(as(testUsers.staff(),"PUT",path,body,null),403,"FORBIDDEN");
        assertError(as(testUsers.customer(SOURCE),"PUT",path,body,null),403,"FORBIDDEN");
        assertError(limit("1001","1000"),400,"INVALID_LIMIT");
        assertError(limit("0","1000"),400,"INVALID_LIMIT");
        assertError(limit("1.5","1000"),400,"INVALID_LIMIT");
        assertError(as(testUsers.bearer(RoleCode.ADMIN,null),"PUT",path,"{\"perTransferAmount\":100,\"dailyAmount\":\"1000\"}",null),400,"INVALID_REQUEST");
        assertThat(count("transfer_limits")).isZero();
        assertThat(actions("TRANSFER_LIMIT_UPDATED")).isZero();
    }
    @Test void requestWaitingForAccountLockUsesTheDayAfterItActuallyAcquiresTheLock() throws Exception {
        Instant before=Instant.parse("2026-10-27T16:59:59Z"),after=Instant.parse("2026-10-27T17:00:00Z");
        when(clock.instant()).thenReturn(before);
        assertThat(limit("1000","1000").statusCode()).isEqualTo(200);
        assertThat(transfer("exhaust-before-wait","1000").statusCode()).isEqualTo(201);
        var request=asRequest(testUsers.customer(SOURCE),"POST","/transfers",payload(SOURCE,DESTINATION,"1000"),"wait-until-new-day");
        java.util.concurrent.CompletableFuture<HttpResponse<String>> pending;
        try (var holder=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (var lock=holder.prepareStatement("SELECT id FROM accounts WHERE id=? FOR UPDATE")) {
                    lock.setObject(1,DESTINATION); lock.executeQuery().close();
                }
                pending=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
                boolean waiting=false; long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(600);
                while (!waiting && System.nanoTime()<deadline) {
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT exists(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%accounts%')",Boolean.class));
                    if (!waiting) Thread.sleep(10);
                }
                assertThat(waiting).as("Transfer reached a real PostgreSQL account lock before clock advance").isTrue();
                when(clock.instant()).thenReturn(after);
                holder.commit();
            } finally { holder.rollback(); }
        }
        var completed=pending.get(5,java.util.concurrent.TimeUnit.SECONDS);
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(201);
        assertThat(json.readTree(completed.body()).get("transaction").get("createdAt").asText()).isEqualTo(after.toString());
        assertLedgerConsistent(2);
    }
    @Test void adminUpdateUsesTheSameSourceAccountWriteLock() throws Exception {
        assertThat(limit("1000","2000").statusCode()).isEqualTo(200);
        try (var holder=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (var lock=holder.prepareStatement("SELECT id FROM accounts WHERE id=? FOR UPDATE")) {
                    lock.setObject(1,SOURCE); lock.executeQuery().close();
                }
                assertError(limit("500","1000"),503,"ACCOUNT_BUSY");
                assertThat(jdbc.queryForObject("SELECT per_transfer_amount FROM transfer_limits WHERE account_id=?",java.math.BigDecimal.class,SOURCE)).isEqualByComparingTo("1000");
            } finally { holder.rollback(); }
        }
        assertThat(limit("500","1000").statusCode()).isEqualTo(200);
        assertError(transfer("after-limit-change","501"),409,"PER_TRANSFER_LIMIT_EXCEEDED");
        assertBalances("1000000","0"); assertLedgerConsistent(0);
    }
}
