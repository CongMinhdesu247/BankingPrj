package com.minh.banking.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class LoginHistoryQueryService {
    private final JdbcTemplate jdbc;
    private final LoginAuditWriter fingerprints;
    public LoginHistoryQueryService(JdbcTemplate jdbc,LoginAuditWriter fingerprints) { this.jdbc=jdbc; this.fingerprints=fingerprints; }
    public long failedBefore(UUID actor,Instant at,int seconds) {
        if (actor==null) return 0;
        var names=jdbc.queryForList("SELECT username FROM users WHERE id=?",String.class,actor);
        if (names.isEmpty()) return 0;
        return jdbc.queryForObject("""
            SELECT count(*) FROM login_history WHERE NOT success AND created_at>? AND created_at<=?
            AND (user_id=? OR (user_id IS NULL AND username_fingerprint=?))
            """,Long.class,Timestamp.from(at.minusSeconds(seconds)),Timestamp.from(at),actor,fingerprints.fingerprint(names.get(0)));
    }
}
