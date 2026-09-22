package com.coffee.management.loyalty.application;

import com.coffee.management.loyalty.domain.Points;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LoyaltyService {
    public record Wallet(UUID customerId, long availablePoints, long reservedPoints) {
    }

    public record Reservation(UUID id, UUID customerId, UUID orderId, long points, String status) {
    }

    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public LoyaltyService(JdbcTemplate db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    public UUID createCustomer(UUID identityUserId, String key, Actor actor) {
        actor.require("loyalty:manage_customer", null);
        String hash = hash(actor.userId() + ":" + identityUserId);
        return tx.execute(t -> {
            UUID prior = claim(key, hash);
            if (prior != null)
                return prior;
            UUID id = UUID.randomUUID();
            db.update("INSERT INTO customer(id,identity_user_id) VALUES (?,?)", id, identityUserId);
            db.update("INSERT INTO point_wallet(customer_id) VALUES (?)", id);
            complete(key, id);
            event(id, "CustomerCreated.v1");
            return id;
        });
    }

    public UUID credit(UUID customerId, long amount, UUID referenceId, String key, Actor actor) {
        actor.require("loyalty:adjust_points", null);
        new Points(amount);
        String hash = hash(actor.userId() + ":" + customerId + ":" + amount + ":" + referenceId);
        return tx.execute(t -> {
            UUID prior = claim(key, hash);
            if (prior != null)
                return prior;
            int changed = db.update(
                    "UPDATE point_wallet SET available_points=available_points+?,version=version+1 WHERE customer_id=?",
                    amount, customerId);
            if (changed != 1)
                throw new IllegalStateException("CUSTOMER_NOT_FOUND");
            UUID id = UUID.randomUUID();
            db.update(
                    "INSERT INTO point_ledger(id,customer_id,points_delta,kind,reference_id,actor_id) VALUES (?,?,?,'CREDIT',?,?)",
                    id, customerId, amount, referenceId, actor.userId());
            complete(key, id);
            event(customerId, "PointsCredited.v1");
            return id;
        });
    }

    public Reservation reserve(UUID customerId, UUID orderId, long amount, Actor actor) {
        actor.require("loyalty:reserve_points", null);
        new Points(amount);
        return tx.execute(t -> {
            Reservation existing = byOrder(orderId);
            if (existing != null) {
                if (!existing.customerId().equals(customerId) || existing.points() != amount)
                    throw new IllegalStateException("RESERVATION_CONFLICT");
                return existing;
            }
            int changed = db.update(
                    "UPDATE point_wallet SET available_points=available_points-?,reserved_points=reserved_points+?,version=version+1 WHERE customer_id=? AND available_points>=?",
                    amount, amount, customerId, amount);
            if (changed != 1)
                throw new IllegalStateException("INSUFFICIENT_POINTS");
            UUID id = UUID.randomUUID();
            db.update(
                    "INSERT INTO point_reservation(id,customer_id,order_id,points,status) VALUES (?,?,?,?,'RESERVED')",
                    id, customerId, orderId, amount);
            event(customerId, "PointsReserved.v1");
            return new Reservation(id, customerId, orderId, amount, "RESERVED");
        });
    }

    public Reservation commit(UUID orderId, Actor actor) {
        return finish(orderId, actor, "COMMITTED");
    }

    public Reservation release(UUID orderId, Actor actor) {
        return finish(orderId, actor, "RELEASED");
    }

    private Reservation finish(UUID orderId, Actor actor, String target) {
        actor.require("loyalty:commit_points", null);
        return tx.execute(t -> {
            var rows = db.query(
                    "SELECT id,customer_id,order_id,points,status FROM point_reservation WHERE order_id=? FOR UPDATE",
                    (rs, n) -> new Reservation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), rs.getLong(4), rs.getString(5)),
                    orderId);
            if (rows.isEmpty())
                throw new IllegalStateException("RESERVATION_NOT_FOUND");
            Reservation r = rows.getFirst();
            if (target.equals(r.status()))
                return r;
            if (!"RESERVED".equals(r.status()))
                throw new IllegalStateException("RESERVATION_ALREADY_FINAL");
            int updated;
            if ("COMMITTED".equals(target)) {
                updated = db.update(
                        "UPDATE point_wallet SET reserved_points=reserved_points-?,version=version+1 WHERE customer_id=? AND reserved_points>=?",
                        r.points(), r.customerId(), r.points());
                db.update(
                        "INSERT INTO point_ledger(id,customer_id,points_delta,kind,reference_id,actor_id) VALUES (?,?,?,'REDEEM',?,?)",
                        UUID.randomUUID(), r.customerId(), -r.points(), orderId, actor.userId());
            } else
                updated = db.update(
                        "UPDATE point_wallet SET reserved_points=reserved_points-?,available_points=available_points+?,version=version+1 WHERE customer_id=? AND reserved_points>=?",
                        r.points(), r.points(), r.customerId(), r.points());
            if (updated != 1)
                throw new IllegalStateException("WALLET_CONFLICT");
            db.update("UPDATE point_reservation SET status=? WHERE id=?", target, r.id());
            event(r.customerId(), "COMMITTED".equals(target) ? "PointsCommitted.v1" : "PointsReleased.v1");
            return new Reservation(r.id(), r.customerId(), orderId, r.points(), target);
        });
    }

    public Wallet wallet(UUID customerId, Actor actor) {
        actor.require("loyalty:view_points", null);
        var rows = db.query("SELECT customer_id,available_points,reserved_points FROM point_wallet WHERE customer_id=?",
                (rs, n) -> new Wallet(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)), customerId);
        if (rows.isEmpty())
            throw new IllegalStateException("CUSTOMER_NOT_FOUND");
        return rows.getFirst();
    }

    private Reservation byOrder(UUID orderId) {
        var rows = db.query("SELECT id,customer_id,order_id,points,status FROM point_reservation WHERE order_id=?",
                (rs, n) -> new Reservation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getLong(4), rs.getString(5)),
                orderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private UUID claim(String key, String hash) {
        if (key == null || key.isBlank() || key.length() > 128)
            throw new IllegalArgumentException("Invalid idempotency key");
        int added = db.update(
                "INSERT INTO command_result(idempotency_key,request_hash,result_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                key, hash, new UUID(0, 0));
        if (added == 1)
            return null;
        var rows = db.queryForList("SELECT request_hash,result_id FROM command_result WHERE idempotency_key=?", key);
        if (rows.isEmpty() || !hash.equals(rows.getFirst().get("request_hash")))
            throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
        return (UUID) rows.getFirst().get("result_id");
    }

    private void complete(String key, UUID id) {
        db.update("UPDATE command_result SET result_id=? WHERE idempotency_key=?", id, key);
    }

    private void event(UUID id, String type) {
        db.update(
                "INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES (?,?,?,jsonb_build_object('id',?::text))",
                UUID.randomUUID(), id, type, id.toString());
    }

    private String hash(String input) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
