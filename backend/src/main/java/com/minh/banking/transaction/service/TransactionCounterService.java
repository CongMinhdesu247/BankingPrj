package com.minh.banking.transaction.service;

import com.minh.banking.redis.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.*;

/** Best effort outgoing-transfer count; never a quota, idempotency or fraud source of truth. */
@Service
public class TransactionCounterService {
    private static final Logger LOG=LoggerFactory.getLogger(TransactionCounterService.class);
    private final RedisStateStore store;
    private final RedisKeyFactory keys;
    private final RedisProperties properties;
    public TransactionCounterService(RedisStateStore store,RedisKeyFactory keys,RedisProperties properties) {
        this.store=store; this.keys=keys; this.properties=properties;
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void committed(TransferCommitted event) {
        try {
            long window=Math.floorDiv(event.occurredAt().getEpochSecond(),properties.counterWindowSeconds());
            store.increment(keys.counter(event.sourceAccountId(),window),properties.counterTtlSeconds());
        } catch (RuntimeException failure) {
            LOG.warn("Redis transaction counter update lost: type={}, requestId={}",failure.getClass().getSimpleName(),MDC.get("requestId"));
        }
    }
}
