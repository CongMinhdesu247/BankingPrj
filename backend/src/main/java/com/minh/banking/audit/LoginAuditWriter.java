package com.minh.banking.audit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LoginAuditWriter {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final SecretKey fingerprintKey;
    public LoginAuditWriter(JdbcTemplate jdbc,AuditService audit,Clock clock,SecretKey jwtSecretKey) {
        this.jdbc=jdbc; this.audit=audit; this.clock=clock;
        // Domain-separated HMAC key; rotating JWT secret changes future login fingerprints.
        this.fingerprintKey=new SecretKeySpec(mac(jwtSecretKey,"banking:login-history:fingerprint:v1"),"HmacSHA256");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(String username,UUID userId,boolean success) {
        String fingerprint=fingerprint(username);
        if (!success && userId==null) {
            var known=jdbc.queryForList("SELECT id FROM users WHERE username=?",UUID.class,username.strip().toLowerCase(Locale.ROOT));
            if (!known.isEmpty()) userId=known.get(0);
        }
        jdbc.update("""
            INSERT INTO login_history(id,user_id,username_fingerprint,success,failure_reason,request_id,created_at)
            VALUES (?,?,?,?,?,?,?)
            """,UUID.randomUUID(),userId,fingerprint,success,success?null:"INVALID_CREDENTIALS",MDC.get("requestId"),Timestamp.from(clock.instant()));
        audit.write(userId,success?AuditService.Action.LOGIN_SUCCEEDED:AuditService.Action.LOGIN_FAILED,
            "USER",userId,success,success?Map.of():Map.of("code","INVALID_CREDENTIALS"));
    }
    public String fingerprint(String username) {
        return HexFormat.of().formatHex(mac(fingerprintKey,username.strip().toLowerCase(Locale.ROOT)));
    }
    private static byte[] mac(SecretKey key,String text) {
        try { var mac=Mac.getInstance("HmacSHA256"); mac.init(key); return mac.doFinal(text.getBytes(StandardCharsets.UTF_8)); }
        catch (GeneralSecurityException exception) { throw new IllegalStateException("Login fingerprint unavailable",exception); }
    }
}
