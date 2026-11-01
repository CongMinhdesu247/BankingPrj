package com.minh.banking.transaction;

import com.minh.banking.account.entity.Account;
import com.minh.banking.account.service.AccountLockService;
import com.minh.banking.transaction.entity.TransactionEntry;
import com.minh.banking.transaction.entity.BankTransaction;
import com.minh.banking.transaction.enums.EntryDirection;
import com.minh.banking.transaction.service.TransactionLedgerWriter;
import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;

class TransferRollbackIT extends TransferIntegrationTestSupport {
    @MockitoSpyBean AccountLockService locks;
    @MockitoSpyBean TransactionLedgerWriter ledger;
    @Autowired EntityManager entityManager;

    @Test
    void failureAfterFlushedDebitRollsBackMoneyVersionAndLedger() throws Exception {
        var snapshot = accountSnapshot();
        var reachedFailurePoint = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            call.callRealMethod();
            entityManager.flush(); // Prove rollback after SQL debit, not just an in-memory exception.
            var stored = (java.math.BigDecimal) entityManager.createNativeQuery("select balance from accounts where id=:id")
                .setParameter("id", SOURCE).getSingleResult();
            assertThat(stored).isEqualByComparingTo("800000.00");
            reachedFailurePoint.set(true);
            throw new IllegalStateException("Injected test failure after debit");
        }).when(org.springframework.test.util.AopTestUtils.<AccountLockService>getUltimateTargetObject(locks))
            .debit(any(Account.class), any(java.math.BigDecimal.class));
        assertError(request("POST", "/transfers", payload(SOURCE, DESTINATION, "200000")), 500, "INTERNAL_ERROR");
        assertThat(reachedFailurePoint).as("SQL debit was verified before injecting the failure").isTrue();
        assertThat(accountSnapshot()).isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='TRANSFER_COMPLETED'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='TRANSFER_FAILED'",Integer.class)).isEqualTo(1);
        assertLedgerConsistent(0);
    }

    @Test
    void failureAfterFirstFlushedEntryRollsBackBothBalancesTransactionAndEntry() throws Exception {
        var snapshot = accountSnapshot();
        var reachedFailurePoint = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            var written = (TransactionEntry) call.callRealMethod();
            var count = (Number) entityManager.createNativeQuery("select count(*) from transaction_entries where id=:id")
                .setParameter("id", written.getId()).getSingleResult();
            assertThat(count.intValue()).isEqualTo(1);
            reachedFailurePoint.set(true); // First entry was actually flushed to PostgreSQL.
            throw new IllegalStateException("Injected test failure after first entry");
        }).when(org.springframework.test.util.AopTestUtils.<TransactionLedgerWriter>getUltimateTargetObject(ledger))
            .record(any(BankTransaction.class), any(Account.class), any(EntryDirection.class));
        assertError(request("POST", "/transfers", payload(SOURCE, DESTINATION, "200000")), 500, "INTERNAL_ERROR");
        assertThat(reachedFailurePoint).as("SQL entry was verified before injecting the failure").isTrue();
        assertThat(accountSnapshot()).isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='TRANSFER_COMPLETED'",Integer.class)).isZero();
        assertLedgerConsistent(0);
    }
}
