package com.coffee.management.identity.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisRefreshLockTest {

    @Test
    void executesActionWhenAtomicAcquireScriptCreatesTheLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, invocation ->
                invocation.getMethod().getName().equals("execute") ? 1L : RETURNS_DEFAULTS.answer(invocation));
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.set(any(byte[].class), any(byte[].class), any(), any())).thenReturn(true);
        RedisRefreshLock lock = new RedisRefreshLock(redis);
        AtomicBoolean executed = new AtomicBoolean();

        String result = lock.execute(UUID.randomUUID(), Duration.ofMillis(50), Duration.ofSeconds(10), () -> {
            executed.set(true);
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(executed).isTrue();
    }
}
