package com.coffee.management.identity.application;

import com.coffee.management.identity.application.port.IdentityRepository;
import com.coffee.management.identity.domain.model.UserAccount;
import com.coffee.management.identity.infrastructure.security.JwtService;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityAdminService {
    private final IdentityRepository repository;
    private final PasswordEncoder passwords;
    private final Clock clock = Clock.systemUTC();

    public IdentityAdminService(IdentityRepository repository, PasswordEncoder passwords) {
        this.repository = repository;
        this.passwords = passwords;
    }

    public Profile profile(JwtService.AccessPrincipal principal) {
        var user = repository.findUser(principal.userId())
                .orElseThrow(() -> new IdentityException("USER_NOT_FOUND", "User not found", 404));
        if (user.securityVersion() != principal.securityVersion() || user.status() != UserAccount.Status.ACTIVE)
            throw IdentityException.unauthorized();
        var auth = repository.authorization(user.id(), clock.instant());
        return new Profile(user.id(), user.username(), user.email(), user.displayName(), auth.permissions(),
                auth.branchScopes(), auth.globalScope());
    }

    @Transactional
    public UUID createUser(String username, String email, String password, String displayName, String idempotencyKey,
            UUID actor, String correlation) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200)
            throw new IdentityException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key is required and must not exceed 200 characters", 422);
        String normalizedUsername = username.trim(), normalizedEmail = blankToNull(email),
                normalizedName = displayName.trim();
        String requestHash = hash(normalizedUsername, normalizedEmail, password, normalizedName);
        Instant now = clock.instant();
        var record = repository.beginIdempotency("CREATE_USER", idempotencyKey.trim(), requestHash,
                now.plus(Duration.ofHours(24)), now);
        if (!record.requestHash().equals(requestHash))
            throw new IdentityException("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request", 409);
        if (!record.newlyReserved()) {
            if (record.resourceId() == null || record.responseStatus() == null)
                throw new IdentityException("IDEMPOTENCY_IN_PROGRESS",
                        "A request with this Idempotency-Key is still being processed", 409);
            return record.resourceId();
        }
        UUID userId = repository.createUser(normalizedUsername, normalizedEmail, passwords.encode(password),
                normalizedName, actor, correlation, now);
        repository.completeIdempotency("CREATE_USER", idempotencyKey.trim(), userId, 201, "{\"id\":\"" + userId + "\"}",
                clock.instant());
        return userId;
    }

    @Transactional
    public void changeStatus(UUID userId, UserAccount.Status status, UUID actor, String correlation) {
        repository.changeUserStatus(userId, status, actor, correlation, clock.instant());
    }

    @Transactional
    public UUID createRole(String code, String name, String description, UUID actor, String correlation) {
        return repository.createRole(code.trim().toUpperCase(), name.trim(), blankToNull(description), actor,
                correlation, clock.instant());
    }

    @Transactional
    public void replacePermissions(UUID roleId, Set<String> permissions, UUID actor, String correlation) {
        repository.replaceRolePermissions(roleId, permissions, actor, correlation, clock.instant());
    }

    @Transactional
    public UUID assignRole(UUID userId, UUID roleId, UUID branchId, JwtService.AccessPrincipal actor,
            String correlation) {
        if (!actor.globalScope() && (branchId == null || !actor.branchScopes().contains(branchId)))
            throw new IdentityException("BRANCH_SCOPE_DENIED", "Actor cannot assign roles in this branch", 403);
        return repository.assignRole(userId, roleId, branchId, actor.userId(), correlation, clock.instant());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                String item = value == null ? "-1:" : value.length() + ":" + value;
                digest.update(item.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash idempotent request", ex);
        }
    }

    public record Profile(UUID id, String username, String email, String displayName, Set<String> permissions,
            Set<UUID> branchScopes, boolean globalScope) {
    }
}
