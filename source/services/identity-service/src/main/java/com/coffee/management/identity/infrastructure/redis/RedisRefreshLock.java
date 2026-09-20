package com.coffee.management.identity.infrastructure.redis;

import com.coffee.management.identity.application.IdentityException;
import com.coffee.management.identity.application.port.RefreshLock;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisRefreshLock implements RefreshLock {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private final StringRedisTemplate redis;

    public RedisRefreshLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public <T> T execute(UUID sessionId, Duration wait, Duration ttl, Supplier<T> action) {
        String key = "lock:refresh:" + sessionId;
        String owner = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + wait.toNanos();
        boolean acquired = false;
        do {
            try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
                byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] ownerBytes = owner.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Boolean created = connection.set(
                        keyBytes,
                        ownerBytes,
                        Expiration.milliseconds(ttl.toMillis()),
                        RedisStringCommands.SetOption.SET_IF_ABSENT);
                acquired = Boolean.TRUE.equals(created)
                        || java.security.MessageDigest.isEqual(ownerBytes, connection.get(keyBytes));
            }
            if (!acquired) {
                try {
                    Thread.sleep(25 + java.util.concurrent.ThreadLocalRandom.current().nextInt(50));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } while (System.nanoTime() < deadline);
        if (!acquired)
            throw new IdentityException("REFRESH_BUSY", "Refresh session is busy", 409);
        try {
            return action.get();
        } finally {
            redis.execute(RELEASE, Collections.singletonList(key), owner);
        }
    }
}
