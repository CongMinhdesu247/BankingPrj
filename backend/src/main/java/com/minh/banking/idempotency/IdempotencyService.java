package com.minh.banking.idempotency;

import com.minh.banking.common.exception.*;
import com.minh.banking.transaction.dto.request.TransferRequest;
import com.minh.banking.transaction.dto.response.TransactionDetailResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyService {
    private static final String OPERATION = "POST:/api/v1/transfers:v1";
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final int lockTimeout;
    public IdempotencyService(JdbcTemplate jdbc, JsonMapper json, Clock clock,
        @Value("${banking.transfer.lock-timeout-ms:2000}") int lockTimeout) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.lockTimeout = lockTimeout;
    }
    public record Claim(UUID id, TransactionDetailResponse replay) {}
    private record Payload(UUID source, UUID destination, String amount, String currency, String description) {}

    public Claim claim(UUID actor, String key, TransferRequest request, BigDecimal amount) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new BusinessException(ErrorCode.INVALID_IDEMPOTENCY_KEY, "Provide an Idempotency-Key of 1-128 letters, digits, dots, underscores, colons or hyphens.");
        }
        String hash = hash(new Payload(request.sourceAccountId(), request.destinationAccountId(),
            amount.toPlainString(), request.currency(), request.description()));
        jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class, lockTimeout + "ms");
        Instant now = clock.instant();
        // Unique-index waiting is bounded. No unique violation is caught inside an aborted transaction.
        var claimed = jdbc.query("""
            INSERT INTO idempotency_keys(id,actor_id,operation,key,request_hash,created_at,expires_at)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(actor_id,operation,key) DO UPDATE SET
              request_hash=EXCLUDED.request_hash,transaction_id=NULL,response_status=NULL,response_body=NULL,
              created_at=EXCLUDED.created_at,expires_at=EXCLUDED.expires_at
            WHERE idempotency_keys.expires_at <= EXCLUDED.created_at
            RETURNING id
            """, (rs, n) -> rs.getObject("id", UUID.class), UUID.randomUUID(), actor, OPERATION, key,
            hash, Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(7))));
        if (!claimed.isEmpty()) return new Claim(claimed.getFirst(), null);
        // Separate READ COMMITTED statement sees the committed concurrent winner.
        return jdbc.queryForObject("""
            SELECT id,request_hash,response_status,response_body::text FROM idempotency_keys
            WHERE actor_id=? AND operation=? AND key=?
            """, (rs, n) -> {
                if (!hash.equals(rs.getString("request_hash"))) throw new BusinessException(
                ErrorCode.IDEMPOTENCY_CONFLICT, "This key has already been used with a different request.");
                String body = rs.getString("response_body");
                if (body == null || rs.getInt("response_status") != 201) throw new BusinessException(
                ErrorCode.IDEMPOTENCY_BUSY, "Request is not complete; retry with the same key.");
                return new Claim(rs.getObject("id", UUID.class), json.readValue(body, TransactionDetailResponse.class));
            }, actor, OPERATION, key);
    }
    public void complete(UUID id, TransactionDetailResponse response) {
        int changed = jdbc.update("""
            UPDATE idempotency_keys SET transaction_id=?,response_status=201,response_body=cast(? as jsonb)
            WHERE id=? AND transaction_id IS NULL
            """, response.transaction().id(), json.writeValueAsString(response), id);
        if (changed != 1) throw new IllegalStateException("Idempotency claim was not owned");
    }
    private String hash(Payload payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
