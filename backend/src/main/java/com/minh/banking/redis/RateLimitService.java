package com.minh.banking.redis;

import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {
    private static final Logger LOG=LoggerFactory.getLogger(RateLimitService.class);
    private final RedisStateStore store;
    private final RedisKeyFactory keys;
    private final RedisProperties properties;
    public RateLimitService(RedisStateStore store,RedisKeyFactory keys,RedisProperties properties) {
        this.store=store; this.keys=keys; this.properties=properties;
    }
    public void check(String route,String actor) {
        long wait;
        try { wait=store.consume(keys.rate(route,actor),properties.rateWindowSeconds(),
                route.equals("login")?properties.loginRateLimit():properties.transferRateLimit()); }
        catch (RuntimeException failure) {
            LOG.warn("Redis rate protection unavailable: route={}, type={}, requestId={}",route,failure.getClass().getSimpleName(),MDC.get("requestId"));
            throw ThrottleException.unavailable();
        }
        if (wait>0) throw ThrottleException.limited(wait);
    }
}
