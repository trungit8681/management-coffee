package com.coffee.management.identity.infrastructure.persistence;

import com.coffee.management.identity.application.IdentityException;
import com.coffee.management.identity.application.port.IdentityRepository;
import com.coffee.management.identity.domain.model.EffectiveAuthorization;
import com.coffee.management.identity.domain.model.UserAccount;
import java.net.InetAddress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdentityRepository implements IdentityRepository {
    private final JdbcTemplate jdbc;

    public JdbcIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findUserForLogin(String identifier) {
        return jdbc.query("""
                SELECT id, username, email, password_hash, display_name, status,
                       failed_login_attempts, locked_until, security_version
                FROM identity_users
                WHERE lower(username) = lower(?) OR lower(email) = lower(?)
                """, this::user, identifier, identifier).stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findUser(UUID id) {
        return jdbc.query("""
                SELECT id, username, email, password_hash, display_name, status,
                       failed_login_attempts, locked_until, security_version
                FROM identity_users WHERE id = ?
                """, this::user, id).stream().findFirst();
    }

    private UserAccount user(ResultSet rs, int row) throws SQLException {
        var locked = rs.getTimestamp("locked_until");
        return new UserAccount(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("email"),
                rs.getString("password_hash"), rs.getString("display_name"),
                UserAccount.Status.valueOf(rs.getString("status")),
                rs.getInt("failed_login_attempts"), locked == null ? null : locked.toInstant(),
                rs.getLong("security_version"));
    }

    @Override
    public EffectiveAuthorization authorization(UUID userId, Instant now) {
        List<AuthRow> rows = jdbc.query("""
                SELECT p.code, ura.branch_id
                FROM user_role_assignments ura
                JOIN roles r ON r.id = ura.role_id AND r.status = 'ACTIVE'
                JOIN role_permissions rp ON rp.role_id = r.id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE ura.user_id = ? AND ura.revoked_at IS NULL
                  AND ura.valid_from <= ? AND (ura.valid_until IS NULL OR ura.valid_until > ?)
                """, (rs, row) -> new AuthRow(rs.getString(1), rs.getObject(2, UUID.class)), userId, ts(now), ts(now));
        Set<String> permissions = new HashSet<>();
        Set<UUID> branches = new HashSet<>();
        boolean global = false;
        for (AuthRow row : rows) {
            permissions.add(row.permission());
            if (row.branchId() == null)
                global = true;
            else
                branches.add(row.branchId());
        }
        return new EffectiveAuthorization(permissions, branches, global);
    }

    @Override
    public void recordLoginFailure(UserAccount user, int maxFailures, Instant lockedUntil) {
        int next = user.failedLoginAttempts() + 1;
        jdbc.update("""
                UPDATE identity_users SET failed_login_attempts = ?, locked_until = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, next, next >= maxFailures ? ts(lockedUntil) : null, user.id());
    }

    @Override
    public void recordLoginSuccess(UUID userId) {
        jdbc.update(
                "UPDATE identity_users SET failed_login_attempts=0, locked_until=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                userId);
    }

    @Override
    public void createSession(UUID sessionId, UUID userId, UUID familyId, UUID tokenJti, String deviceId,
            String deviceName, String userAgent, String ipAddress, Instant expiresAt,
            String tokenHash, Instant now) {
        jdbc.update(
                """
                        INSERT INTO refresh_sessions(id,user_id,token_family_id,current_token_jti,device_id,device_name,user_agent,ip_address,expires_at,last_rotated_at,created_at)
                        VALUES (?,?,?,?,?,?,?,CAST(? AS inet),?,?,?)
                        """,
                sessionId, userId, familyId, tokenJti, deviceId, deviceName, userAgent, ipAddress, ts(expiresAt),
                ts(now), ts(now));
        jdbc.update("""
                INSERT INTO refresh_tokens(jti,session_id,token_hash,issued_at,expires_at) VALUES (?,?,?,?,?)
                """, tokenJti, sessionId, tokenHash, ts(now), ts(expiresAt));
    }

    @Override
    public Optional<RefreshTokenState> lockRefreshToken(UUID sessionId, UUID tokenJti) {
        return jdbc.query("""
                SELECT s.id, s.user_id, s.current_token_jti, t.token_hash, t.expires_at token_expires_at,
                       s.expires_at session_expires_at, t.used_at, t.revoked_at token_revoked_at,
                       s.revoked_at session_revoked_at, u.security_version, u.status
                FROM refresh_sessions s JOIN refresh_tokens t ON t.session_id=s.id AND t.jti=?
                JOIN identity_users u ON u.id=s.user_id WHERE s.id=? FOR UPDATE OF s, t
                """,
                (rs, row) -> new RefreshTokenState(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("current_token_jti", UUID.class), rs.getString("token_hash"),
                        rs.getTimestamp("token_expires_at").toInstant(),
                        rs.getTimestamp("session_expires_at").toInstant(),
                        instant(rs, "used_at"), instant(rs, "token_revoked_at"), instant(rs, "session_revoked_at"),
                        rs.getLong("security_version"), UserAccount.Status.valueOf(rs.getString("status"))),
                tokenJti, sessionId)
                .stream().findFirst();
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    @Override
    public void rotateRefreshToken(UUID sessionId, UUID oldJti, UUID newJti, String newHash, Instant expiresAt,
            Instant now) {
        jdbc.update("UPDATE refresh_tokens SET used_at=? WHERE jti=? AND used_at IS NULL", ts(now), oldJti);
        jdbc.update(
                "INSERT INTO refresh_tokens(jti,session_id,parent_jti,token_hash,issued_at,expires_at) VALUES (?,?,?,?,?,?)",
                newJti, sessionId, oldJti, newHash, ts(now), ts(expiresAt));
        jdbc.update("UPDATE refresh_sessions SET current_token_jti=?,last_rotated_at=?,version=version+1 WHERE id=?",
                newJti, ts(now), sessionId);
    }

    @Override
    public void revokeSession(UUID sessionId, String reason, Instant now) {
        jdbc.update(
                "UPDATE refresh_sessions SET revoked_at=COALESCE(revoked_at,?),revoke_reason=COALESCE(revoke_reason,?),version=version+1 WHERE id=?",
                ts(now), reason, sessionId);
        jdbc.update(
                "UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,?),revoke_reason=COALESCE(revoke_reason,?) WHERE session_id=? AND used_at IS NULL",
                ts(now), reason, sessionId);
    }

    @Override
    public void revokeAllSessions(UUID userId, String reason, Instant now) {
        jdbc.update(
                "UPDATE refresh_sessions SET revoked_at=COALESCE(revoked_at,?),revoke_reason=COALESCE(revoke_reason,?),version=version+1 WHERE user_id=?",
                ts(now), reason, userId);
        jdbc.update(
                "UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,?),revoke_reason=COALESCE(revoke_reason,?) WHERE session_id IN (SELECT id FROM refresh_sessions WHERE user_id=?)",
                ts(now), reason, userId);
    }

    @Override
    public void audit(String type, UUID actorId, UUID subjectId, UUID branchId, String correlationId,
            String requestId, String ipAddress, String outcome, String reasonCode, Instant now) {
        jdbc.update(
                """
                        INSERT INTO identity_audit_events(id,event_type,actor_user_id,subject_user_id,branch_id,correlation_id,request_id,ip_address,outcome,reason_code,occurred_at)
                        VALUES (?,?,?,?,?,?,?,CAST(? AS inet),?,?,?)
                        """,
                UUID.randomUUID(), type, actorId, subjectId, branchId, correlationId, requestId, ipAddress, outcome,
                reasonCode, ts(now));
    }

    @Override
    public UUID createUser(String username, String email, String passwordHash, String displayName, UUID actorId,
            String correlationId, Instant now) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update(
                    "INSERT INTO identity_users(id,username,email,password_hash,display_name,status,created_at,updated_at) VALUES (?,?,?,?,?,'ACTIVE',?,?)",
                    id, username, email, passwordHash, displayName, ts(now), ts(now));
            outbox("User", id, "IdentityUserCreated", correlationId, now);
            audit("ACCOUNT_CREATED", actorId, id, null, correlationId, null, null, "SUCCESS", null, now);
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw new IdentityException("ACCOUNT_CONFLICT", "Username or email already exists", 409);
        }
    }

    @Override
    public IdempotencyRecord beginIdempotency(String operation, String key, String requestHash, Instant expiresAt,
            Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO idempotency_records(id,operation,idempotency_key,request_hash,expires_at,created_at)
                VALUES (?,?,?,?,?,?) ON CONFLICT (operation,idempotency_key) DO NOTHING
                """, UUID.randomUUID(), operation, key, requestHash, ts(expiresAt), ts(now));
        if (inserted == 1)
            return new IdempotencyRecord(true, requestHash, null, null, null);
        return jdbc.query("""
                SELECT request_hash,resource_id,response_status,response_body::text
                FROM idempotency_records WHERE operation=? AND idempotency_key=?
                """, (rs, row) -> new IdempotencyRecord(false, rs.getString(1), rs.getObject(2, UUID.class),
                (Integer) rs.getObject(3), rs.getString(4)), operation, key).stream().findFirst()
                .orElseThrow(() -> new IdentityException("IDEMPOTENCY_CONFLICT",
                        "Idempotency request is in an invalid state", 409));
    }

    @Override
    public void completeIdempotency(String operation, String key, UUID resourceId, int responseStatus,
            String responseBody, Instant now) {
        int updated = jdbc.update(
                """
                        UPDATE idempotency_records SET resource_id=?,response_status=?,response_body=CAST(? AS jsonb),completed_at=?
                        WHERE operation=? AND idempotency_key=? AND completed_at IS NULL
                        """,
                resourceId, responseStatus, responseBody, ts(now), operation, key);
        if (updated != 1)
            throw new IdentityException("IDEMPOTENCY_CONFLICT", "Idempotency request could not be completed", 409);
    }

    @Override
    public void changeUserStatus(UUID userId, UserAccount.Status status, UUID actorId, String correlationId,
            Instant now) {
        int changed = jdbc.update(
                "UPDATE identity_users SET status=?,security_version=security_version+1,version=version+1,updated_at=? WHERE id=?",
                status.name(), ts(now), userId);
        if (changed == 0)
            throw new IdentityException("USER_NOT_FOUND", "User not found", 404);
        revokeAllSessions(userId, "ACCOUNT_STATUS_CHANGED", now);
        outbox("User", userId, "IdentityUserStatusChanged", correlationId, now);
        audit("ACCOUNT_STATUS_CHANGED", actorId, userId, null, correlationId, null, null, "SUCCESS", status.name(),
                now);
    }

    @Override
    public UUID createRole(String code, String name, String description, UUID actorId, String correlationId,
            Instant now) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO roles(id,code,name,description,created_at,updated_at) VALUES (?,?,?,?,?,?)", id,
                    code, name, description, ts(now), ts(now));
        } catch (DataIntegrityViolationException ex) {
            throw new IdentityException("ROLE_CONFLICT", "Role code already exists", 409);
        }
        outbox("Role", id, "IdentityRoleCreated", correlationId, now);
        audit("ROLE_CREATED", actorId, null, null, correlationId, null, null, "SUCCESS", null, now);
        return id;
    }

    @Override
    public void replaceRolePermissions(UUID roleId, Set<String> codes, UUID actorId, String correlationId,
            Instant now) {
        Boolean roleExists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM roles WHERE id=?)", Boolean.class,
                roleId);
        if (!Boolean.TRUE.equals(roleExists))
            throw new IdentityException("ROLE_NOT_FOUND", "Role not found", 404);
        for (String code : codes) {
            Boolean permissionExists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM permissions WHERE code=?)",
                    Boolean.class, code);
            if (!Boolean.TRUE.equals(permissionExists))
                throw new IdentityException("PERMISSION_NOT_FOUND", "One or more permissions do not exist", 422);
        }
        jdbc.update("DELETE FROM role_permissions WHERE role_id=?", roleId);
        for (String code : codes)
            jdbc.update(
                    "INSERT INTO role_permissions(role_id,permission_id,granted_by) SELECT ?,id,? FROM permissions WHERE code=?",
                    roleId, actorId, code);
        outbox("Role", roleId, "IdentityRolePermissionsChanged", correlationId, now);
        audit("ROLE_PERMISSIONS_CHANGED", actorId, null, null, correlationId, null, null, "SUCCESS", null, now);
    }

    @Override
    public UUID assignRole(UUID userId, UUID roleId, UUID branchId, UUID actorId, String correlationId, Instant now) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update(
                    "INSERT INTO user_role_assignments(id,user_id,role_id,branch_id,assigned_by,created_at) VALUES (?,?,?,?,?,?)",
                    id, userId, roleId, branchId, actorId, ts(now));
        } catch (DataIntegrityViolationException ex) {
            throw new IdentityException("ASSIGNMENT_CONFLICT",
                    "Active role assignment already exists or reference is invalid", 409);
        }
        jdbc.update("UPDATE identity_users SET security_version=security_version+1,updated_at=? WHERE id=?", ts(now),
                userId);
        revokeAllSessions(userId, "AUTHORIZATION_CHANGED", now);
        outbox("User", userId, "IdentityRoleAssigned", correlationId, now);
        audit("ROLE_ASSIGNED", actorId, userId, branchId, correlationId, null, null, "SUCCESS", null, now);
        return id;
    }

    @Override
    public boolean bootstrapAdminExists(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM identity_users WHERE lower(username)=lower(?))", Boolean.class, username));
    }

    @Override
    public UUID bootstrapAdmin(String username, String passwordHash, String displayName, Instant now) {
        UUID user = createUser(username, null, passwordHash, displayName, null, "bootstrap", now);
        assignRole(user, UUID.fromString("20000000-0000-0000-0000-000000000001"), null, null, "bootstrap", now);
        return user;
    }

    private void outbox(String aggregateType, UUID aggregateId, String eventType, String correlationId, Instant now) {
        jdbc.update(
                "INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,correlation_id,occurred_at) VALUES (?,?,?,?,CAST(? AS jsonb),?,?)",
                UUID.randomUUID(), aggregateType, aggregateId, eventType, "{\"aggregateId\":\"" + aggregateId + "\"}",
                correlationId, ts(now));
    }

    private Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    private record AuthRow(String permission, UUID branchId) {
    }
}
