package com.coffee.management.identity.application;

import com.coffee.management.identity.application.port.IdentityRepository;
import com.coffee.management.identity.application.port.RefreshLock;
import com.coffee.management.identity.domain.model.UserAccount;
import com.coffee.management.identity.infrastructure.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthApplicationService {
    private final IdentityRepository repository;
    private final RefreshLock refreshLock;
    private final JwtService jwt;
    private final PasswordEncoder passwords;
    private final TransactionTemplate tx;
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();
    private final Duration refreshTtl;
    private final int maxFailures;
    private final Duration lockDuration;

    public AuthApplicationService(IdentityRepository repository, RefreshLock refreshLock, JwtService jwt,
            PasswordEncoder passwords, PlatformTransactionManager transactionManager,
            @Value("${identity.jwt.refresh-ttl}") Duration refreshTtl,
            @Value("${identity.login.max-failures}") int maxFailures,
            @Value("${identity.login.lock-duration}") Duration lockDuration) {
        this.repository = repository;
        this.refreshLock = refreshLock;
        this.jwt = jwt;
        this.passwords = passwords;
        this.tx = new TransactionTemplate(transactionManager);
        this.refreshTtl = refreshTtl;
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
    }

    public TokenPair login(LoginCommand command) {
        Instant now = clock.instant();
        UserAccount user = repository.findUserForLogin(command.identifier().trim()).orElse(null);
        if (user == null) {
            auditFailure(null, command, "UNKNOWN_ACCOUNT", now);
            throw IdentityException.unauthorized();
        }
        if (!user.canAuthenticate(now)) {
            auditFailure(user.id(), command, "ACCOUNT_UNAVAILABLE", now);
            throw new IdentityException("ACCOUNT_LOCKED", "Account is locked or disabled", 423);
        }
        if (!passwords.matches(command.password(), user.passwordHash())) {
            tx.executeWithoutResult(s -> {
                repository.recordLoginFailure(user, maxFailures, now.plus(lockDuration));
                auditFailure(user.id(), command, "INVALID_CREDENTIALS", now);
            });
            throw IdentityException.unauthorized();
        }
        return tx.execute(s -> {
            repository.recordLoginSuccess(user.id());
            var auth = repository.authorization(user.id(), now);
            UUID sessionId = UUID.randomUUID(), jti = UUID.randomUUID();
            String refresh = refreshToken(sessionId, jti);
            repository.createSession(sessionId, user.id(), UUID.randomUUID(), jti, command.deviceId(),
                    command.deviceName(), command.userAgent(), command.ipAddress(), now.plus(refreshTtl), hash(refresh),
                    now);
            repository.audit("LOGIN_SUCCEEDED", user.id(), user.id(), null, command.correlationId(),
                    command.requestId(), command.ipAddress(), "SUCCESS", null, now);
            var access = jwt.issue(user.id(), user.securityVersion(), auth, now);
            return new TokenPair(access.value(), access.expiresAt(), refresh, now.plus(refreshTtl), auth.permissions(),
                    auth.branchScopes(), auth.globalScope());
        });
    }

    public TokenPair refresh(String token, String correlationId, String requestId, String ipAddress) {
        RefreshParts parts = parse(token);
        TokenPair result = refreshLock.execute(parts.sessionId(), Duration.ofSeconds(2), Duration.ofSeconds(10),
                () -> tx.execute(s -> {
                    Instant now = clock.instant();
                    var state = repository.lockRefreshToken(parts.sessionId(), parts.jti())
                            .orElseThrow(IdentityException::unauthorized);
                    boolean reuse = state.tokenUsedAt() != null || !state.currentJti().equals(parts.jti());
                    boolean invalid = state.sessionRevokedAt() != null || state.tokenRevokedAt() != null
                            || !state.tokenExpiresAt().isAfter(now)
                            || !state.sessionExpiresAt().isAfter(now) || state.userStatus() != UserAccount.Status.ACTIVE
                            || !constantEquals(state.tokenHash(), hash(token));
                    if (reuse) {
                        repository.revokeSession(parts.sessionId(), "REFRESH_TOKEN_REUSE", now);
                        repository.audit("REFRESH_REUSE_DETECTED", state.userId(), state.userId(), null, correlationId,
                                requestId, ipAddress, "DENIED", "TOKEN_REUSE", now);
                        return null;
                    }
                    if (invalid) {
                        repository.audit("REFRESH_FAILED", state.userId(), state.userId(), null, correlationId,
                                requestId, ipAddress, "FAILURE", "INVALID_REFRESH_TOKEN", now);
                        return null;
                    }
                    UUID nextJti = UUID.randomUUID();
                    String next = refreshToken(parts.sessionId(), nextJti);
                    Instant expires = now.plus(refreshTtl);
                    repository.rotateRefreshToken(parts.sessionId(), parts.jti(), nextJti, hash(next), expires, now);
                    repository.audit("REFRESH_SUCCEEDED", state.userId(), state.userId(), null, correlationId,
                            requestId, ipAddress, "SUCCESS", null, now);
                    var auth = repository.authorization(state.userId(), now);
                    var access = jwt.issue(state.userId(), state.securityVersion(), auth, now);
                    return new TokenPair(access.value(), access.expiresAt(), next, expires, auth.permissions(),
                            auth.branchScopes(), auth.globalScope());
                }));
        if (result == null)
            throw IdentityException.unauthorized();
        return result;
    }

    public void logout(String refreshToken, String correlationId, String requestId, String ipAddress) {
        RefreshParts parts = parse(refreshToken);
        Instant now = clock.instant();
        refreshLock.execute(parts.sessionId(), Duration.ofSeconds(2), Duration.ofSeconds(10), () -> tx.execute(s -> {
            var state = repository.lockRefreshToken(parts.sessionId(), parts.jti()).orElse(null);
            repository.revokeSession(parts.sessionId(), "LOGOUT", now);
            repository.audit("LOGOUT", state == null ? null : state.userId(), state == null ? null : state.userId(),
                    null, correlationId, requestId, ipAddress, "SUCCESS", null, now);
            return null;
        }));
    }

    private void auditFailure(UUID userId, LoginCommand c, String reason, Instant now) {
        repository.audit("LOGIN_FAILED", userId, userId, null, c.correlationId(), c.requestId(), c.ipAddress(),
                "FAILURE", reason, now);
    }

    private String refreshToken(UUID sessionId, UUID jti) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return sessionId + "." + jti + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private RefreshParts parse(String value) {
        try {
            String[] p = value.split("\\.");
            if (p.length != 3)
                throw new IllegalArgumentException();
            return new RefreshParts(UUID.fromString(p[0]), UUID.fromString(p[1]));
        } catch (RuntimeException ex) {
            throw IdentityException.unauthorized();
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    public record LoginCommand(String identifier, String password, String deviceId, String deviceName, String userAgent,
            String ipAddress, String correlationId, String requestId) {
    }

    public record TokenPair(String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt,
            java.util.Set<String> permissions, java.util.Set<UUID> branchScopes, boolean globalScope) {
    }

    private record RefreshParts(UUID sessionId, UUID jti) {
    }
}
