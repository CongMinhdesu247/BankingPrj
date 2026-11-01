package com.minh.banking.transaction.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Published read API for fraud; only committed INTERNAL_TRANSFER facts are eligible. */
@Service @Transactional(readOnly=true)
public class TransactionFraudQueryService {
    public record Fact(UUID id,UUID sourceAccountId,UUID destinationAccountId,UUID customerId,UUID actorId,BigDecimal amount,Instant at) {}
    private final JdbcTemplate jdbc;
    public TransactionFraudQueryService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<Fact> find(UUID id) {
        return jdbc.query("""
            SELECT t.*,a.customer_id FROM transactions t JOIN accounts a ON a.id=t.source_account_id
            WHERE t.id=? AND t.type='INTERNAL_TRANSFER' AND t.status='COMPLETED'
            """,(rs,n)->new Fact(rs.getObject("id",UUID.class),rs.getObject("source_account_id",UUID.class),
                rs.getObject("destination_account_id",UUID.class),rs.getObject("customer_id",UUID.class),rs.getObject("initiated_by",UUID.class),
                rs.getBigDecimal("amount"),rs.getTimestamp("created_at").toInstant()),id).stream().findFirst();
    }
    public long countOutgoing(Fact fact,int windowSeconds) {
        // (lower, current tuple], includes this transfer but excludes future and greater-ID ties.
        return jdbc.queryForObject("""
            SELECT count(*) FROM transactions WHERE source_account_id=? AND type='INTERNAL_TRANSFER' AND status='COMPLETED'
            AND created_at>? AND (created_at,id)<=(?,?)
            """,Long.class,fact.sourceAccountId(),Timestamp.from(fact.at().minusSeconds(windowSeconds)),Timestamp.from(fact.at()),fact.id());
    }
}
