package com.minh.banking.fraud;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class FraudEventQueryService {
    public record Fact(UUID alertId,UUID transactionId,UUID sourceAccountId,int score,String riskLevel,Instant at) {}
    private final JdbcTemplate jdbc;
    public FraudEventQueryService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<Fact> find(UUID id) {
        return jdbc.query("""
            SELECT f.*,t.source_account_id FROM fraud_alerts f JOIN transactions t ON t.id=f.transaction_id WHERE f.id=?
            """,(rs,n)->new Fact(rs.getObject("id",UUID.class),rs.getObject("transaction_id",UUID.class),rs.getObject("source_account_id",UUID.class),
                rs.getInt("score"),rs.getString("risk_level"),rs.getTimestamp("created_at").toInstant()),id).stream().findFirst();
    }
}
