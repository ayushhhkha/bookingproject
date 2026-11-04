package com.example.booking.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRY = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(String key, int limit, Duration window) {
        try {
            Long count = redis.execute(INCREMENT_WITH_EXPIRY, List.of(key), String.valueOf(window.toSeconds()));
            return count == null || count <= limit;
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable, allowing request ({})", e.getMessage());
            return true;
        }
    }

    public long retryAfterSeconds(String key) {
        try {
            Long ttl = redis.getExpire(key);
            return ttl == null || ttl < 1 ? 1 : ttl;
        } catch (DataAccessException e) {
            return 1;
        }
    }
}