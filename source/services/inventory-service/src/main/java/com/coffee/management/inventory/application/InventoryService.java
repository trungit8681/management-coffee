package com.coffee.management.inventory.application;

import com.coffee.management.inventory.domain.StockMovement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryService {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    public InventoryService(JdbcTemplate db, TransactionTemplate transactions) {
        this.db = db;
        this.transactions = transactions;
    }

    public UUID createIngredient(String code, String name, String unit, String key, Actor actor) {
        actor.require("inventory:manage_ingredient", null);
        if (code == null || code.isBlank() || name == null || name.isBlank() || unit == null || unit.isBlank())
            throw new IllegalArgumentException("Invalid ingredient");
        String hash = hash(actor.userId() + ":" + code + ":" + name + ":" + unit);
        return transactions.execute(tx -> {
            UUID id = claim(key, hash);
            if (id != null)
                return id;
            id = UUID.randomUUID();
            db.update("INSERT INTO ingredient(id,code,name,unit) VALUES (?,?,?,?)", id, code, name, unit);
            db.update("UPDATE command_result SET result_id=? WHERE idempotency_key=?", id, key);
            outbox(id, "IngredientCreated.v1", "ingredientId", id);
            return id;
        });
    }

    public UUID move(StockMovement movement, String key, Actor actor) {
        actor.require("IN".equals(movement.kind()) ? "inventory:receive" : "inventory:deduct", movement.branchId());
        String hash = hash(actor.userId() + ":" + movement);
        return transactions.execute(tx -> {
            UUID prior = claim(key, hash);
            if (prior != null)
                return prior;
            int updated;
            if ("IN".equals(movement.kind())) {
                updated = db.update(
                        "INSERT INTO stock_balance(branch_id,ingredient_id,quantity) VALUES (?,?,?) ON CONFLICT(branch_id,ingredient_id) DO UPDATE SET quantity=stock_balance.quantity+EXCLUDED.quantity,version=stock_balance.version+1",
                        movement.branchId(), movement.ingredientId(), movement.quantity());
            } else {
                updated = db.update(
                        "UPDATE stock_balance SET quantity=quantity-?,version=version+1 WHERE branch_id=? AND ingredient_id=? AND quantity>=?",
                        movement.quantity(), movement.branchId(), movement.ingredientId(), movement.quantity());
            }
            if (updated != 1)
                throw new IllegalStateException("INSUFFICIENT_STOCK");
            UUID id = UUID.randomUUID();
            db.update(
                    "INSERT INTO stock_ledger(id,branch_id,ingredient_id,quantity_delta,kind,reference_id,actor_id) VALUES (?,?,?,?,?,?,?)",
                    id, movement.branchId(), movement.ingredientId(), movement.signedQuantity(), movement.kind(),
                    movement.referenceId(), actor.userId());
            db.update("UPDATE command_result SET result_id=? WHERE idempotency_key=?", id, key);
            outbox(id, "StockMoved.v1", "movementId", id);
            return id;
        });
    }

    public long balance(UUID branchId, UUID ingredientId, Actor actor) {
        actor.require("inventory:view", branchId);
        var rows = db.queryForList("SELECT quantity FROM stock_balance WHERE branch_id=? AND ingredient_id=?",
                Long.class, branchId, ingredientId);
        return rows.isEmpty() ? 0 : rows.getFirst();
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

    private void outbox(UUID aggregate, String type, String field, UUID value) {
        db.update(
                "INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES (?,?,?,jsonb_build_object(?::text,?::text))",
                UUID.randomUUID(), aggregate, type, field, value.toString());
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
