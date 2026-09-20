package com.coffee.management.identity.application.port;

import com.coffee.management.identity.domain.model.EffectiveAuthorization;
import com.coffee.management.identity.domain.model.UserAccount;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdentityRepository {
    Optional<UserAccount> findUserForLogin(String identifier);

    Optional<UserAccount> findUser(UUID userId);

    EffectiveAuthorization authorization(UUID userId, Instant now);

    void recordLoginFailure(UserAccount user, int maxFailures, Instant lockedUntil);

    void recordLoginSuccess(UUID userId);

    void createSession(UUID sessionId, UUID userId, UUID familyId, UUID tokenJti, String deviceId,
            String deviceName, String userAgent, String ipAddress, Instant expiresAt,
            String tokenHash, Instant now);

    Optional<RefreshTokenState> lockRefreshToken(UUID sessionId, UUID tokenJti);

    void rotateRefreshToken(UUID sessionId, UUID oldJti, UUID newJti, String newHash, Instant expiresAt, Instant now);

    void revokeSession(UUID sessionId, String reason, Instant now);

    void revokeAllSessions(UUID userId, String reason, Instant now);

    void audit(String type, UUID actorId, UUID subjectId, UUID branchId, String correlationId,
            String requestId, String ipAddress, String outcome, String reasonCode, Instant now);

    UUID createUser(String username, String email, String passwordHash, String displayName, UUID actorId,
            String correlationId, Instant now);

    IdempotencyRecord beginIdempotency(String operation, String key, String requestHash, Instant expiresAt,
            Instant now);

    void completeIdempotency(String operation, String key, UUID resourceId, int responseStatus, String responseBody,
            Instant now);

    void changeUserStatus(UUID userId, UserAccount.Status status, UUID actorId, String correlationId, Instant now);

    UUID createRole(String code, String name, String description, UUID actorId, String correlationId, Instant now);

    void replaceRolePermissions(UUID roleId, java.util.Set<String> permissionCodes, UUID actorId,
            String correlationId, Instant now);

    UUID assignRole(UUID userId, UUID roleId, UUID branchId, UUID actorId, String correlationId, Instant now);

    boolean bootstrapAdminExists(String username);

    UUID bootstrapAdmin(String username, String passwordHash, String displayName, Instant now);

    record RefreshTokenState(UUID sessionId, UUID userId, UUID currentJti, String tokenHash,
            Instant tokenExpiresAt, Instant sessionExpiresAt, Instant tokenUsedAt,
            Instant tokenRevokedAt, Instant sessionRevokedAt, long securityVersion,
            UserAccount.Status userStatus) {
    }

    record IdempotencyRecord(boolean newlyReserved, String requestHash, UUID resourceId,
            Integer responseStatus, String responseBody) {
    }
}
