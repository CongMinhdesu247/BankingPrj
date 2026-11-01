package com.minh.banking.security;

import com.minh.banking.kafka.*;
import com.minh.banking.transaction.dto.request.TransferRequest;
import com.minh.banking.transaction.service.TransferService;
import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties={"banking.kafka.enabled=true","banking.kafka.outbox-initial-delay-ms=3600000"})
class OutboxSecurityIT extends TransferIntegrationTestSupport {
    @Autowired OutboxRelay relay;
    @Autowired TransferService transfers;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean KafkaDomainEventPublisher publisher;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-28T05:00:00Z"));
    @BeforeEach void configureClock() { when(clock.instant()).thenAnswer(call->now.get()); }
    private void transfer(String key) {
        testUsers.asCustomer(SOURCE,()->transfers.transfer(key,new TransferRequest(SOURCE,DESTINATION,"1000","VND",null)));
    }
    private int rows() { return jdbc.queryForObject("SELECT count(*) FROM outbox_events",Integer.class); }

    @Test void rollbackAndIdempotentRequestsDoNotLeaveExtraEvents() {
        testUsers.asCustomer(SOURCE,()->new TransactionTemplate(manager).executeWithoutResult(status->{
            transfers.transfer("rolled-back",new TransferRequest(SOURCE,DESTINATION,"1","VND",null)); status.setRollbackOnly();
        }));
        assertThat(rows()).isZero(); assertLedgerConsistent(0);
        transfer("same-intent"); transfer("same-intent");
        assertThat(rows()).isEqualTo(1); assertLedgerConsistent(1);
    }
    @Test void brokerFailureIsDurableAndRetryPreservesTheExactEnvelope() {
        transfer("broker-outage");
        doThrow(new IllegalStateException("broker unavailable")).doNothing().when(publisher).publishConfirmed(any(),anyString());
        assertThat(relay.relayOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events",String.class)).isEqualTo("FAILED");
        assertThat(relay.relayOne()).isFalse(); // Backoff, not a busy loop.
        now.set(now.get().plusSeconds(6));
        assertThat(relay.relayOne()).isTrue();
        var captured=org.mockito.ArgumentCaptor.forClass(EventEnvelope.class);
        verify(publisher,times(2)).publishConfirmed(captured.capture(),eq(SOURCE.toString()));
        assertThat(captured.getAllValues().get(0)).isEqualTo(captured.getAllValues().get(1));
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events",String.class)).isEqualTo("PUBLISHED");
        assertLedgerConsistent(1);
    }
    @Test void crashAfterAcknowledgementBeforeDatabaseCommitCanRetrySafely() {
        transfer("ack-before-crash");
        new TransactionTemplate(manager).executeWithoutResult(status->{ assertThat(relay.relayOne()).isTrue(); status.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events",String.class)).isEqualTo("PENDING");
        assertThat(relay.relayOne()).isTrue();
        var captured=org.mockito.ArgumentCaptor.forClass(EventEnvelope.class);
        verify(publisher,times(2)).publishConfirmed(captured.capture(),anyString());
        assertThat(captured.getAllValues().get(0)).isEqualTo(captured.getAllValues().get(1));
        assertLedgerConsistent(1);
    }
    @Test void concurrentRelaysDoNotPublishTheSameLockedRow() throws Exception {
        transfer("concurrent-relay");
        var sending=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call->{ sending.countDown(); if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timeout"); return null; })
            .when(publisher).publishConfirmed(any(),anyString());
        try (var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->relay.relayOne());
            try { assertThat(sending.await(5,TimeUnit.SECONDS)).isTrue(); assertThat(relay.relayOne()).isFalse(); }
            finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        }
        verify(publisher).publishConfirmed(any(),anyString()); assertLedgerConsistent(1);
    }
    @Test void enqueueFailureRollsBackMoneyLedgerAndIdempotency() {
        jdbc.execute("CREATE FUNCTION reject_outbox_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test outbox failure'; END; $$");
        jdbc.execute("CREATE TRIGGER reject_outbox_test BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION reject_outbox_test()");
        try {
            assertThatThrownBy(()->transfer("enqueue-fails")).isInstanceOf(RuntimeException.class);
            assertThat(rows()).isZero(); assertLedgerConsistent(0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys",Integer.class)).isZero();
        } finally { jdbc.execute("DROP TRIGGER reject_outbox_test ON outbox_events"); jdbc.execute("DROP FUNCTION reject_outbox_test()"); }
    }
}
