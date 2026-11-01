package com.minh.banking.transaction.support;

import com.minh.banking.account.service.AccountLockService;
import com.minh.banking.transaction.entity.*;
import com.minh.banking.transaction.enums.*;
import com.minh.banking.transaction.repository.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@TestConfiguration(proxyBeanMethods = false)
public class BankingTestFixtures {
    @Bean
    FixtureWriter fixtureWriter(JdbcTemplate jdbc, AccountLockService locks, EntityManager entityManager,
            BankTransactionRepository transactions, TransactionEntryRepository entries) {
        return new FixtureWriter(jdbc, locks, entityManager, transactions, entries);
    }

    public static class FixtureWriter {
        public static final UUID TREASURY = UUID.fromString("00000000-0000-0000-0000-000000000001");
        public static final UUID SOURCE = UUID.fromString("80000000-0000-0000-0000-000000000002");
        public static final UUID DESTINATION = UUID.fromString("10000000-0000-0000-0000-000000000003");
        public static final BigDecimal RESERVE = new BigDecimal("10000000.00");
        private final JdbcTemplate jdbc;
        private final AccountLockService locks;
        private final EntityManager entityManager;
        private final BankTransactionRepository transactions;
        private final TransactionEntryRepository entries;

        FixtureWriter(JdbcTemplate jdbc, AccountLockService locks, EntityManager entityManager,
                BankTransactionRepository transactions, TransactionEntryRepository entries) {
            this.jdbc = jdbc;
            this.locks = locks;
            this.entityManager = entityManager;
            this.transactions = transactions;
            this.entries = entries;
        }

        @Transactional
        public void seed() {
            if (!"banking_test".equals(jdbc.queryForObject("select current_database()", String.class))) {
                throw new IllegalStateException("Fixture may only write banking_test");
            }
            UUID customer = UUID.randomUUID();
            UUID treasuryCustomer = UUID.randomUUID();
            jdbc.update("insert into customers(id,customer_code,full_name) values (?,'TRANSFER_FIXTURE','Test Customer')", customer);
            jdbc.update("insert into customers(id,customer_code,full_name) values (?,'TRANSFER_TREASURY','Test Treasury')", treasuryCustomer);
            for (var id : new UUID[] {TREASURY, SOURCE, DESTINATION}) {
                jdbc.update("insert into accounts(id,customer_id,account_number) values (?,?,?)",
                    id, id.equals(TREASURY) ? treasuryCustomer : customer, "TEST-" + id.toString().substring(0, 8));
            }
            var treasury = locks.lockOne(TREASURY);
            // Sole opening-book exception: test-only reserve document = RESERVE at TREASURY.
            // Even this fixture write uses the shared account lock and the same transaction.
            jdbc.update("update accounts set balance=?,version=version+1,updated_at=current_timestamp where id=?", RESERVE, TREASURY);
            entityManager.refresh(treasury);
            allocate(SOURCE, new BigDecimal("1000000.00"));
        }

        @Transactional
        public void allocate(UUID destination, BigDecimal amount) {
            var pair = locks.lockPair(TREASURY, destination);
            locks.debit(pair.source(), amount);
            locks.credit(pair.destination(), amount);
            var opening = transactions.saveAndFlush(new BankTransaction(pair.source(), pair.destination(), amount,
                TransactionType.OPENING, "Test-only treasury allocation"));
            entries.saveAndFlush(new TransactionEntry(opening, pair.source(), EntryDirection.DEBIT));
            entries.saveAndFlush(new TransactionEntry(opening, pair.destination(), EntryDirection.CREDIT));
        }
    }
}
