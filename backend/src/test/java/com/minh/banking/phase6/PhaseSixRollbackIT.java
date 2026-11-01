package com.minh.banking.phase6;

import com.minh.banking.audit.AuditService;
import com.minh.banking.idempotency.IdempotencyService;
import com.minh.banking.transaction.dto.response.TransactionDetailResponse;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

class PhaseSixRollbackIT extends PhaseSixSupport {
    @MockitoSpyBean AuditService audit;
    @MockitoSpyBean IdempotencyService idempotency;
    @Test void failureAfterSuccessAuditInsertRollsBackAuditMoneyLedgerAndKeyTogether() throws Exception {
        var snapshot=accountSnapshot();
        var reached=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{
            call.callRealMethod();
            assertThat(actions("TRANSFER_COMPLETED")).isEqualTo(1); // Actual JDBC insert inside JPA transaction.
            reached.set(true);
            throw new IllegalStateException("Injected failure after audit insert");
        }).when(AopTestUtils.<AuditService>getUltimateTargetObject(audit))
            .success(eq(AuditService.Action.TRANSFER_COMPLETED),eq("TRANSACTION"),any(UUID.class),anyMap());
        assertError(transfer("audit-rollback","1000"),500,"INTERNAL_ERROR");
        assertThat(reached).isTrue(); assertThat(accountSnapshot()).isEqualTo(snapshot);
        assertThat(count("idempotency_keys")).isZero(); assertThat(actions("TRANSFER_COMPLETED")).isZero();
        assertThat(actions("TRANSFER_FAILED")).isEqualTo(1); assertLedgerConsistent(0);
    }
    @Test void failureAfterSnapshotUpdateRollsBackAndSameKeyCanBeRetried() throws Exception {
        var snapshot=accountSnapshot();
        var reached=new java.util.concurrent.atomic.AtomicBoolean();
        var target=AopTestUtils.<IdempotencyService>getUltimateTargetObject(idempotency);
        doAnswer(call->{
            call.callRealMethod();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE response_body IS NOT NULL",Integer.class)).isEqualTo(1);
            reached.set(true);
            throw new IllegalStateException("Injected failure after response snapshot");
        }).when(target).complete(any(UUID.class),any(TransactionDetailResponse.class));
        assertError(transfer("snapshot-rollback","1000"),500,"INTERNAL_ERROR");
        assertThat(reached).isTrue(); assertThat(accountSnapshot()).isEqualTo(snapshot);
        assertThat(count("idempotency_keys")).isZero(); assertThat(actions("TRANSFER_COMPLETED")).isZero();
        assertThat(actions("TRANSFER_FAILED")).isEqualTo(1); assertLedgerConsistent(0);
        org.mockito.Mockito.doCallRealMethod().when(target).complete(any(UUID.class),any(TransactionDetailResponse.class));
        assertThat(transfer("snapshot-rollback","1000").statusCode()).isEqualTo(201);
        assertThat(count("idempotency_keys")).isEqualTo(1); assertLedgerConsistent(1);
    }
}
