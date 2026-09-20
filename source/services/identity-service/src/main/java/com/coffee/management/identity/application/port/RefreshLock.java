package com.coffee.management.identity.application.port;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

public interface RefreshLock {
    <T> T execute(UUID sessionId, Duration wait, Duration ttl, Supplier<T> action);
}
