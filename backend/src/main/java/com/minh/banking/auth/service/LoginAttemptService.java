package com.minh.banking.auth.service;

import com.minh.banking.redis.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {
    private static final Logger LOG=LoggerFactory.getLogger(LoginAttemptService.class);
    private final RedisStateStore store;
    private final RedisKeyFactory keys;
    private final RedisProperties properties;
    public LoginAttemptService(RedisStateStore store,RedisKeyFactory keys,RedisProperties properties) {
        this.store=store; this.keys=keys; this.properties=properties;
    }
    public void check(String username) {
        long wait;
        // Reserve BEFORE password verification. Separate check/increment operations
        // admit every concurrent request that observes the same old failure count.
        try { wait=store.consume(keys.login(username),properties.loginTtlSeconds(),properties.loginMaxFailures()); }
        catch (RuntimeException failure) { throw unavailable(failure); }
        if (wait>0) throw ThrottleException.limited(wait);
    }
    public void succeeded(String username) {
        try { store.delete(keys.login(username)); }
        catch (RuntimeException failure) { throw unavailable(failure); }
    }
    private ThrottleException unavailable(RuntimeException failure) {
        LOG.warn("Redis login protection unavailable: type={}, requestId={}",failure.getClass().getSimpleName(),MDC.get("requestId"));
        return ThrottleException.unavailable();
    }
}
