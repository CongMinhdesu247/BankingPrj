package com.minh.banking.beneficiary;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class BeneficiaryHistoryService {
    private final JdbcTemplate jdbc;
    public BeneficiaryHistoryService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<Instant> activeSince(UUID customer,UUID destination,Instant at) {
        return jdbc.query("""
            SELECT created_at FROM beneficiaries WHERE owner_customer_id=? AND destination_account_id=?
            AND created_at<=? AND (deleted_at IS NULL OR deleted_at>?) ORDER BY created_at DESC,id DESC LIMIT 1
            """,(rs,n)->rs.getTimestamp("created_at").toInstant(),customer,destination,Timestamp.from(at),Timestamp.from(at)).stream().findFirst();
    }
}
