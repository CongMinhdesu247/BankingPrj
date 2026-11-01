package com.minh.banking.customer.service;

import com.minh.banking.customer.dto.response.CustomerResponse;
import com.minh.banking.redis.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.slf4j.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.*;
import tools.jackson.databind.json.JsonMapper;

/** Cache only a display DTO. Security and active-customer decisions always use PostgreSQL. */
@Service
public class CustomerCacheService {
    private static final Logger LOG=LoggerFactory.getLogger(CustomerCacheService.class);
    private static final String LOADING="loading:";
    private static final DefaultRedisScript<Long> FILL=new DefaultRedisScript<>("""
        if redis.call('GET',KEYS[1])==ARGV[1] then
          redis.call('SET',KEYS[1],ARGV[2],'PX',ARGV[3]); return 1
        end
        return 0
        """,Long.class);
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("""
        if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end
        return 0
        """,Long.class);
    private final StringRedisTemplate redis;
    private final RedisKeyFactory keys;
    private final RedisProperties properties;
    private final JsonMapper json;
    private final Object[] readLocks=new Object[64];
    private final LongAdder hits=new LongAdder(), misses=new LongAdder(), errors=new LongAdder();
    public CustomerCacheService(StringRedisTemplate redis,RedisKeyFactory keys,RedisProperties properties,JsonMapper json) {
        this.redis=redis; this.keys=keys; this.properties=properties; this.json=json;
        Arrays.setAll(readLocks,index->new Object());
    }
    public CustomerResponse get(UUID id,Supplier<CustomerResponse> loader) {
        synchronized (readLocks[Math.floorMod(id.hashCode(),readLocks.length)]) { return load(id,loader); }
    }
    private CustomerResponse load(UUID id,Supplier<CustomerResponse> loader) {
        String key=keys.customer(id),token=LOADING+UUID.randomUUID();
        boolean reserved=false;
        try {
            String value=redis.opsForValue().get(key);
            if (value!=null && !value.startsWith(LOADING)) {
                var cached=json.readValue(value,CustomerResponse.class);
                if (!id.equals(cached.id())) throw new IllegalStateException("Cache identity mismatch");
                hits.increment(); LOG.debug("Customer cache hit"); return cached;
            }
            reserved=Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key,token,
                Duration.ofSeconds(Math.min(5,properties.cacheTtlSeconds()))));
        } catch (RuntimeException failure) { unavailable("read",failure); }
        misses.increment(); LOG.debug("Customer cache miss");
        // Do not catch database/business failures as cache errors or load twice.
        CustomerResponse result;
        try { result=loader.get(); }
        catch (RuntimeException failure) {
            if (reserved) release(key,token);
            throw failure;
        }
        if (reserved) {
            try { redis.execute(FILL,List.of(key),token,json.writeValueAsString(result),Long.toString(properties.cacheTtlSeconds()*1000L)); }
            catch (RuntimeException failure) { unavailable("fill",failure); }
        }
        return result;
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void changed(CustomerProfileChanged event) { invalidate(event.customerId()); }
    public void invalidate(UUID id) {
        try { redis.delete(keys.customer(id)); }
        catch (RuntimeException failure) { unavailable("invalidate",failure); }
    }
    public record Stats(long hits,long misses,long errors) {}
    public Stats stats() { return new Stats(hits.sum(),misses.sum(),errors.sum()); }
    private void release(String key,String token) {
        try { redis.execute(RELEASE,List.of(key),token); }
        catch (RuntimeException failure) { unavailable("release",failure); }
    }
    private void unavailable(String operation,RuntimeException failure) {
        errors.increment();
        LOG.warn("Redis customer cache fallback: operation={}, type={}, requestId={}",operation,failure.getClass().getSimpleName(),MDC.get("requestId"));
    }
}
