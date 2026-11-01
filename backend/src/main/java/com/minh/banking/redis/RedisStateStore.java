package com.minh.banking.redis;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Single-key scripts: count and TTL always change atomically, including TTL repair. */
@Component
public class RedisStateStore {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> CONSUME = script("""
        local n=tonumber(redis.call('GET',KEYS[1]) or '0')
        if n>=tonumber(ARGV[2]) then
          local ttl=redis.call('PTTL',KEYS[1])
          if ttl<0 then redis.call('PEXPIRE',KEYS[1],ARGV[1]); ttl=tonumber(ARGV[1]) end
          return math.max(1,ttl)
        end
        redis.call('INCR',KEYS[1])
        if redis.call('PTTL',KEYS[1])<0 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end
        return 0
        """);
    private static final DefaultRedisScript<Long> CHECK = script("""
        local n=tonumber(redis.call('GET',KEYS[1]) or '0')
        if n==0 then return 0 end
        local ttl=redis.call('PTTL',KEYS[1])
        if ttl<0 then redis.call('PEXPIRE',KEYS[1],ARGV[1]); ttl=tonumber(ARGV[1]) end
        if n>=tonumber(ARGV[2]) then return math.max(1,ttl) end
        return 0
        """);
    private static final DefaultRedisScript<Long> INCREMENT = script("""
        local n=redis.call('INCR',KEYS[1])
        if redis.call('PTTL',KEYS[1])<0 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end
        return n
        """);
    public RedisStateStore(StringRedisTemplate redis) { this.redis=redis; }
    public long consume(String key,int seconds,int limit) { return execute(CONSUME,key,seconds,limit); }
    public long check(String key,int seconds,int limit) { return execute(CHECK,key,seconds,limit); }
    public long increment(String key,int seconds) { return execute(INCREMENT,key,seconds,0); }
    public void delete(String key) { redis.delete(key); }
    private long execute(DefaultRedisScript<Long> script,String key,int seconds,int limit) {
        Long value=redis.execute(script,List.of(key),Long.toString(seconds*1000L),Integer.toString(limit));
        if (value==null) throw new IllegalStateException("Missing Redis script result");
        return value;
    }
    private static DefaultRedisScript<Long> script(String text) { return new DefaultRedisScript<>(text,Long.class); }
}
