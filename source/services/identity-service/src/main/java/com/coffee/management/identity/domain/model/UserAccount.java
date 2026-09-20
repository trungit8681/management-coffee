package com.coffee.management.identity.domain.model;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String username,
        String email,
        String passwordHash,
        String displayName,
        Status status,
        int failedLoginAttempts,
        Instant lockedUntil,
        long securityVersion) {

    public enum Status {
        PENDING, ACTIVE, LOCKED, DISABLED
    }

    public boolean canAuthenticate(Instant now) {
        return status == Status.ACTIVE && (lockedUntil == null || !lockedUntil.isAfter(now));
    }
}
