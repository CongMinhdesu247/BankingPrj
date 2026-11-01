package com.minh.banking.security;

import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import static org.assertj.core.api.Assertions.*;

class EvidenceIntegrityIT extends TransferIntegrationTestSupport {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @org.springframework.beans.factory.annotation.Autowired com.minh.banking.transaction.service.TransferService transfers;

    @Test void ordinaryDmlCannotRewriteOrDeleteAuditOrLedgerEvidence() throws Exception {
        assertThat(request("POST","/transfers",payload(
            com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.SOURCE,
            com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.DESTINATION,"1")).statusCode()).isEqualTo(201);
        for (String table:java.util.List.of("audit_logs","login_history","transactions","transaction_entries")) {
            for (String sql:java.util.List.of("UPDATE "+table+" SET id=id","DELETE FROM "+table)) {
                assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(DataAccessException.class)
                    .rootCause().isInstanceOf(java.sql.SQLException.class).hasMessageContaining("Evidence is append-only");
            }
        }
        assertLedgerConsistent(1);
    }
    @Test void runtimeRoleSupportsTransfersButCannotTruncateOrDisableEvidenceProtection() throws Exception {
        jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of("../infra/postgres/runtime-role.sql")));
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        for (String denied:java.util.List.of("TRUNCATE audit_logs CASCADE","DELETE FROM audit_logs",
                "UPDATE transaction_entries SET amount=1","ALTER TABLE audit_logs DISABLE TRIGGER ALL")) {
            assertThatThrownBy(()->tx.executeWithoutResult(status->{
                jdbc.execute("SET LOCAL ROLE banking_runtime"); jdbc.execute(denied);
            })).isInstanceOf(DataAccessException.class);
        }
        testUsers.asCustomer(com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.SOURCE,
            ()->tx.executeWithoutResult(status->{
                jdbc.execute("SET LOCAL ROLE banking_runtime");
                transfers.transfer("least-privilege-transfer",new com.minh.banking.transaction.dto.request.TransferRequest(
                    com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.SOURCE,
                    com.minh.banking.transaction.support.BankingTestFixtures.FixtureWriter.DESTINATION,"1","VND",null));
            }));
        assertLedgerConsistent(1);
    }
}
