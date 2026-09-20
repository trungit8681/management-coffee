package com.coffee.management.procurement.application;

import com.coffee.management.procurement.domain.PurchaseOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProcurementService {
    public record PurchaseSnapshot(UUID id, UUID branchId, UUID supplierId, UUID ingredientId, long quantity,
            long unitCostVnd, String status, long version) {
    }

    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public ProcurementService(JdbcTemplate db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    public UUID supplier(String code, String name, String key, Actor actor) {
        actor.require("procurement:manage_supplier", null);
        if (code == null || code.isBlank() || name == null || name.isBlank())
            throw new IllegalArgumentException("Invalid supplier");
        String hash = hash(actor.userId() + ":" + code + ":" + name);
        return tx.execute(t -> {
            UUID prior = claim(key, hash);
            if (prior != null)
                return prior;
            UUID id = UUID.randomUUID();
            db.update("INSERT INTO supplier(id,code,name,status) VALUES (?,?,?,'ACTIVE')", id, code, name);
            complete(key, id);
            event(id, "SupplierCreated.v1");
            return id;
        });
    }

    public UUID create(UUID branchId, UUID supplierId, UUID ingredientId, long quantity, long unitCostVnd, String key,
            Actor actor) {
        actor.require("procurement:create_po", branchId);
        var po = new PurchaseOrder(UUID.randomUUID(), branchId, supplierId, ingredientId, quantity, unitCostVnd,
                "PENDING");
        String hash = hash(actor.userId() + ":" + branchId + ":" + supplierId + ":" + ingredientId + ":" + quantity
                + ":" + unitCostVnd);
        return tx.execute(t -> {
            UUID prior = claim(key, hash);
            if (prior != null)
                return prior;
            int rows = db.update(
                    "INSERT INTO purchase_order(id,branch_id,supplier_id,ingredient_id,quantity,unit_cost_vnd,status,created_by) SELECT ?,?,?,?,?,?,'PENDING',? FROM supplier WHERE id=? AND status='ACTIVE'",
                    po.id(), branchId, supplierId, ingredientId, quantity, unitCostVnd, actor.userId(), supplierId);
            if (rows != 1)
                throw new IllegalStateException("SUPPLIER_NOT_ACTIVE");
            complete(key, po.id());
            event(po.id(), "PurchaseOrderCreated.v1");
            return po.id();
        });
    }

    public PurchaseSnapshot approve(UUID id, long expectedVersion, Actor actor) {
        PurchaseSnapshot po = find(id);
        if (po == null)
            throw new IllegalStateException("PO_NOT_FOUND");
        actor.require("procurement:approve_po", po.branchId());
        return tx.execute(t -> {
            int changed = db.update(
                    "UPDATE purchase_order SET status='APPROVED',approved_by=?,version=version+1 WHERE id=? AND status='PENDING' AND version=?",
                    actor.userId(), id, expectedVersion);
            if (changed != 1)
                throw new IllegalStateException("PO_STATE_CONFLICT");
            event(id, "PurchaseOrderApproved.v1");
            return find(id);
        });
    }

    public PurchaseSnapshot get(UUID id, Actor actor) {
        PurchaseSnapshot po = find(id);
        if (po == null)
            throw new IllegalStateException("PO_NOT_FOUND");
        actor.require("procurement:view_po", po.branchId());
        return po;
    }

    private PurchaseSnapshot find(UUID id) {
        var rows = db.query(
                "SELECT id,branch_id,supplier_id,ingredient_id,quantity,unit_cost_vnd,status,version FROM purchase_order WHERE id=?",
                (rs, n) -> new PurchaseSnapshot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getLong(5), rs.getLong(6),
                        rs.getString(7), rs.getLong(8)),
                id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private UUID claim(String key, String hash) {
        if (key == null || key.isBlank() || key.length() > 128)
            throw new IllegalArgumentException("Invalid idempotency key");
        int inserted = db.update(
                "INSERT INTO command_result(idempotency_key,request_hash,result_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                key, hash, new UUID(0, 0));
        if (inserted == 1)
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
