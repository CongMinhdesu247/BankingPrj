package com.minh.banking.transaction;

import com.minh.banking.account.entity.Account;
import com.minh.banking.transaction.dto.request.TransferRequest;
import com.minh.banking.transaction.service.TransferService;
import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.minh.banking.common.exception.BusinessException;
import static org.assertj.core.api.Assertions.*;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class TransferConcurrencyIT extends TransferIntegrationTestSupport {
    @Autowired TransferService transfers;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;

    @Test
    void simultaneousOverspendAllowsExactlyOneTransferAndNoNegativeBalance() throws Exception {
        var responses = runTogether(
            () -> request("POST", "/transfers", payload(SOURCE, DESTINATION, "700000")),
            () -> request("POST", "/transfers", payload(SOURCE, DESTINATION, "700000")));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
        var loser = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
        assertError(loser, 409, "INSUFFICIENT_FUNDS");
        assertBalances("300000", "700000");
        assertLedgerConsistent(1);
    }

    @Test
    void oppositeDirectionTransfersUseSameLockOrderAndPreserveTotalAcrossThreeRounds() throws Exception {
        fixtures.allocate(DESTINATION, new BigDecimal("1000000.00"));
        for (int round = 0; round < 3; round++) {
            var responses = runTogether(
                () -> request("POST", "/transfers", payload(SOURCE, DESTINATION, "100000")),
                () -> request("POST", "/transfers", payload(DESTINATION, SOURCE, "100000")));
            assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactly(201, 201);
        }
        assertBalances("1000000", "1000000");
        assertLedgerConsistent(6);
    }

    @Test
    void closingDestinationAndTransferCoordinateSoClosedAccountNeverReceivesMoney() throws Exception {
        var responses = runTogether(
            () -> request("POST", "/transfers", payload(SOURCE, DESTINATION, "200000")),
            () -> request("PATCH", "/accounts/" + DESTINATION + "/status", "{\"status\":\"CLOSED\"}"));
        if (responses.get(0).statusCode() == 201) {
            assertError(responses.get(1), 409, "ACCOUNT_BALANCE_NOT_ZERO");
            assertBalances("800000", "200000");
            assertLedgerConsistent(1);
        } else {
            assertError(responses.get(0), 409, "ACCOUNT_NOT_ACTIVE");
            assertThat(responses.get(1).statusCode()).isEqualTo(200);
            assertBalances("1000000", "0");
            assertLedgerConsistent(0);
        }
        assertThat(jdbc.queryForObject("select count(*) from accounts where status='CLOSED' and balance<>0", Integer.class)).isZero();
    }

    @Test
    void lockTimeoutIsBoundedRollsBackAndReleasesTransactionForNextRequest() throws Exception {
        var snapshot = accountSnapshot();
        try (var holder = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (var lock = holder.prepareStatement("select id from accounts where id=? for update")) {
                    lock.setObject(1, DESTINATION);
                    lock.executeQuery().close();
                }
                long start = System.nanoTime();
                var response = request("POST", "/transfers", payload(SOURCE, DESTINATION, "200000"));
                assertError(response, 503, "ACCOUNT_BUSY");
                assertThat(response.headers().firstValue("Retry-After")).contains("1");
                assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(5));
                assertThat(accountSnapshot()).isEqualTo(snapshot);
            } finally { holder.rollback(); }
        }
        assertThat(request("POST", "/transfers", payload(SOURCE, DESTINATION, "200000")).statusCode()).isEqualTo(201);
        assertBalances("800000", "200000");
        assertLedgerConsistent(1);
    }

    @Test
    void checksFreshStatusAfterLockEvenIfAccountWasAlreadyManagedBeforeFreeze() throws Exception {
        String result = withStaleAccountThen(
            () -> assertThat(request("PATCH", "/accounts/" + SOURCE + "/status", "{\"status\":\"FROZEN\"}").statusCode())
                .isEqualTo(200));
        assertThat(result).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertBalances("1000000", "0");
        assertLedgerConsistent(0);
    }

    @Test
    void checksFreshBalanceAfterLockEvenIfAccountWasAlreadyManagedBeforeAnotherTransfer() throws Exception {
        String result = withStaleAccountThen(
            () -> assertThat(request("POST", "/transfers", payload(SOURCE, DESTINATION, "900000")).statusCode())
                .isEqualTo(201));
        assertThat(result).isEqualTo("INSUFFICIENT_FUNDS");
        assertBalances("100000", "900000");
        assertLedgerConsistent(1);
    }

    private String withStaleAccountThen(CheckedAction interveningAction) throws Exception {
        var loaded = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var worker = executor.submit(() -> {
                var template = new TransactionTemplate(transactionManager);
                template.setTimeout(10);
                try {
                    template.executeWithoutResult(status -> {
                        entityManager.find(Account.class, SOURCE); // Intentionally stale persistence context.
                        loaded.countDown();
                        try {
                            if (!proceed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture gate timed out");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                        testUsers.asCustomer(SOURCE, () -> transfers.transfer(UUID.randomUUID().toString(),new TransferRequest(SOURCE, DESTINATION, "200000", "VND", null)));
                    });
                    return "COMPLETED";
                } catch (BusinessException exception) { return exception.getCode(); }
            });
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            interveningAction.run();
            proceed.countDown();
            return worker.get(10, TimeUnit.SECONDS); // Worker failures are propagated to JUnit.
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<HttpResponse<String>> runTogether(Callable<HttpResponse<String>> first,
            Callable<HttpResponse<String>> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return first.call(); });
            var b = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(12, TimeUnit.SECONDS), b.get(12, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @FunctionalInterface interface CheckedAction { void run() throws Exception; }
}
