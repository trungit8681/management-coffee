package com.coffee.management.order.infrastructure.persistence;

import com.coffee.management.order.application.OrderException;
import com.coffee.management.order.domain.Order;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrderRepository {
    public record Snapshot(UUID id, UUID branchId, String channel, String status, String paymentStatus, long totalVnd,
            UUID paymentId, long version) {
    }

    public record RecipeIngredient(UUID ingredientId, long quantity) {
    }

    public record PricedItem(Order.Item item, long priceVersion, long recipeVersion,
            List<RecipeIngredient> recipe) {
    }

    public record Saga(UUID orderId, String key, String hash, String status, UUID paymentId, String error) {}
    public record SagaStep(String type, UUID resourceId, long quantity, String status) {}

    private final JdbcTemplate db;

    public JdbcOrderRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Snapshot byKey(String key, String hash) {
        var rows = db.query(
                "SELECT id,branch_id,channel,status,payment_status,total_vnd,payment_id,version,request_hash FROM customer_order WHERE idempotency_key=?",
                (rs, n) -> new Existing(new Snapshot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getObject(7, UUID.class),
                        rs.getLong(8)), rs.getString(9)),
                key);
        if (rows.isEmpty())
            return null;
        if (!rows.getFirst().hash().equals(hash))
            throw new OrderException(409, "IDEMPOTENCY_CONFLICT", "Key used for another order");
        return rows.getFirst().snapshot();
    }

    public Snapshot find(UUID id) {
        var rows = db.query(
                "SELECT id,branch_id,channel,status,payment_status,total_vnd,payment_id,version FROM customer_order WHERE id=?",
                (rs, n) -> new Snapshot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getObject(7, UUID.class), rs.getLong(8)),
                id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Snapshot create(Order order, List<PricedItem> items, UUID actorId, String key, String hash) {
        String paymentStatus = "PENDING_CASH";
        int inserted = db.update(
                "INSERT INTO customer_order(id,branch_id,channel,status,payment_status,total_vnd,subtotal_vnd,actor_id,idempotency_key,request_hash) VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                order.id(), order.branchId(), order.channel(), order.status(), paymentStatus, order.totalVnd(), order.totalVnd(), actorId,
                key, hash);
        if (inserted == 0) {
            var existing = byKey(key, hash);
            if (existing != null)
                return existing;
            throw new OrderException(409, "ORDER_CONFLICT", "Order conflict");
        }
        for (PricedItem priced : items) {
            Order.Item item = priced.item();
            UUID orderItemId = UUID.randomUUID();
            db.update(
                    "INSERT INTO order_item(id,order_id,variant_id,quantity,unit_price_vnd,price_version,line_total_vnd,recipe_version) VALUES (?,?,?,?,?,?,?,?)",
                    orderItemId, order.id(), item.variantId(), item.quantity(), item.unitPriceVnd(),
                    priced.priceVersion(), item.lineTotalVnd(), priced.recipeVersion());
            for (RecipeIngredient ingredient : priced.recipe())
                db.update(
                        "INSERT INTO order_item_recipe(order_item_id,ingredient_id,quantity) VALUES (?,?,?)",
                        orderItemId, ingredient.ingredientId(), Math.multiplyExact(item.quantity(), ingredient.quantity()));
        }
        db.update(
                "INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES (?,?,?,jsonb_build_object('orderId',?::text,'branchId',?::text,'totalVnd',?::bigint,'channel',?::text))",
                UUID.randomUUID(), order.id(), "OrderCreated.v1", order.id().toString(), order.branchId().toString(),
                order.totalVnd(), order.channel());
        return find(order.id());
    }

    public Map<UUID, Long> requiredStock(UUID orderId) {
        Map<UUID, Long> result = new LinkedHashMap<>();
        db.query("SELECT r.ingredient_id,SUM(r.quantity) FROM order_item_recipe r JOIN order_item i ON i.id=r.order_item_id WHERE i.order_id=? GROUP BY r.ingredient_id ORDER BY r.ingredient_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getObject(1, UUID.class),
                        rs.getLong(2)),
                orderId);
        return result;
    }

    public Saga saga(UUID orderId) {
        var rows=db.query("SELECT order_id,idempotency_key,request_hash,status,payment_id,last_error FROM checkout_saga WHERE order_id=?",
                (rs,n)->new Saga(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getObject(5,UUID.class),rs.getString(6)),orderId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    public Saga startSaga(UUID orderId,String key,String hash,String voucher,UUID customer,long points,long cash) {
        db.update("INSERT INTO checkout_saga(order_id,idempotency_key,request_hash,voucher_code,customer_id,points,cash_received_vnd,status) VALUES (?,?,?,?,?,?,?,'STARTED') ON CONFLICT DO NOTHING",
                orderId,key,hash,voucher,customer,points,cash);
        Saga saga=saga(orderId);
        if (saga==null || !saga.key().equals(key) || !saga.hash().equals(hash))
            throw new OrderException(409,"CHECKOUT_IDEMPOTENCY_CONFLICT","Order checkout already uses another request");
        return saga;
    }

    public void sagaStatus(UUID orderId,String status,UUID paymentId,String error) {
        db.update("UPDATE checkout_saga SET status=?,payment_id=COALESCE(?,payment_id),last_error=?,updated_at=now() WHERE order_id=?",status,paymentId,error,orderId);
        db.update("INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES (?,?,?,jsonb_build_object('orderId',?::text,'status',?::text))",
                UUID.randomUUID(),orderId,"CheckoutSagaStateChanged.v1",orderId.toString(),status);
    }

    public boolean acquireSaga(UUID orderId,UUID runner) {
        return db.update("UPDATE checkout_saga SET runner_id=?,lease_until=now()+interval '30 seconds',updated_at=now() WHERE order_id=? AND (runner_id IS NULL OR lease_until<now())",runner,orderId)==1;
    }

    public void releaseSaga(UUID orderId,UUID runner) {
        db.update("UPDATE checkout_saga SET runner_id=NULL,lease_until=NULL,updated_at=now() WHERE order_id=? AND runner_id=?",orderId,runner);
    }

    public void sagaStep(UUID orderId,String type,UUID resourceId,long quantity) {
        db.update("INSERT INTO checkout_saga_step(order_id,step_type,resource_id,quantity,status) VALUES (?,?,?,?,'DONE') ON CONFLICT DO NOTHING",orderId,type,resourceId,quantity);
    }

    public List<SagaStep> sagaSteps(UUID orderId,String type) {
        return db.query("SELECT step_type,resource_id,quantity,status FROM checkout_saga_step WHERE order_id=? AND step_type=? ORDER BY resource_id",
                (rs,n)->new SagaStep(rs.getString(1),rs.getObject(2,UUID.class),rs.getLong(3),rs.getString(4)),orderId,type);
    }

    public void compensated(UUID orderId,String type,UUID resourceId) {
        db.update("UPDATE checkout_saga_step SET status='COMPENSATED' WHERE order_id=? AND step_type=? AND resource_id=?",orderId,type,resourceId);
    }

    public Snapshot applyDiscount(UUID orderId,long voucher,long points) {
        int changed=db.update("UPDATE customer_order SET voucher_discount_vnd=?,points_discount_vnd=?,total_vnd=subtotal_vnd-?-?,version=version+1 WHERE id=? AND payment_status='PENDING_CASH' AND subtotal_vnd-?-?>0",
                voucher,points,voucher,points,orderId,voucher,points);
        if(changed!=1) throw new OrderException(409,"INVALID_DISCOUNT","Discount must be smaller than order subtotal");
        return find(orderId);
    }

    public void clearDiscount(UUID orderId) {
        db.update("UPDATE customer_order SET total_vnd=subtotal_vnd,voucher_discount_vnd=0,points_discount_vnd=0,version=version+1 WHERE id=? AND payment_status='PENDING_CASH'",orderId);
    }

    public Snapshot confirm(UUID id, UUID paymentId, long paidTotal, UUID branchId) {
        Snapshot current = find(id);
        if (current == null)
            throw new OrderException(404, "ORDER_NOT_FOUND", "Order not found");
        if (!current.branchId().equals(branchId) || current.totalVnd() != paidTotal)
            throw new OrderException(409, "PAYMENT_MISMATCH", "Payment does not match order");
        if (current.paymentId() != null) {
            if (current.paymentId().equals(paymentId) && "PAID".equals(current.paymentStatus()))
                return current;
            throw new OrderException(409, "ORDER_ALREADY_PAID", "Order already paid");
        }
        int updated = db.update(
                "UPDATE customer_order SET payment_id=?,payment_status='PAID',status='CONFIRMED',version=version+1 WHERE id=? AND payment_status='PENDING_CASH' AND payment_id IS NULL AND status IN ('AWAITING_CASH','CONFIRMED')",
                paymentId, id);
        if (updated != 1)
            throw new OrderException(409, "ORDER_CHANGED", "Order changed while confirming");
        db.update(
                "INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES (?,?,?,jsonb_build_object('orderId',?::text,'paymentId',?::text))",
                UUID.randomUUID(), id, "OrderCashConfirmed.v1", id.toString(), paymentId.toString());
        return find(id);
    }

    public Snapshot cancel(UUID id, long version, String reason, UUID approverId) {
        Snapshot current = find(id);
        if (current == null)
            throw new OrderException(404, "ORDER_NOT_FOUND", "Order not found");
        String target = "PAID".equals(current.paymentStatus()) ? "REFUND_PENDING" : "CANCELLED";
        int updated = db.update(
                "UPDATE customer_order SET status=?,cancel_reason=?,approved_by=?,version=version+1 WHERE id=? AND version=? AND status IN ('AWAITING_CASH','CONFIRMED')",
                target, reason, approverId, id, version);
        if (updated != 1)
            throw new OrderException(409, "ORDER_STATE_CONFLICT", "Order cannot be cancelled at this version");
        db.update(
                "INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES (?,?,?,jsonb_build_object('orderId',?::text,'status',?::text))",
                UUID.randomUUID(), id, "OrderCancellationRequested.v1", id.toString(), target);
        return find(id);
    }

    public Snapshot completeRefund(UUID id, UUID refundId, UUID branchId, long amountVnd) {
        Snapshot current = find(id);
        if (current == null)
            throw new OrderException(404, "ORDER_NOT_FOUND", "Order not found");
        if (!current.branchId().equals(branchId) || current.totalVnd() != amountVnd)
            throw new OrderException(409, "REFUND_MISMATCH", "Refund does not match order");
        var prior = db.queryForList("SELECT refund_id FROM customer_order WHERE id=?", UUID.class, id).getFirst();
        if (prior != null) {
            if (prior.equals(refundId) && "REFUNDED".equals(current.status()))
                return current;
            throw new OrderException(409, "ORDER_ALREADY_REFUNDED", "Order already refunded");
        }
        int changed = db.update(
                "UPDATE customer_order SET status='REFUNDED',payment_status='REFUNDED',refund_id=?,version=version+1 WHERE id=? AND status IN ('REFUND_PENDING','CANCELLED') AND payment_status IN ('PAID','PENDING_CASH') AND refund_id IS NULL",
                refundId, id);
        if (changed != 1)
            throw new OrderException(409, "ORDER_STATE_CONFLICT", "Order not awaiting refund");
        db.update(
                "INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES (?,?,?,jsonb_build_object('orderId',?::text,'refundId',?::text))",
                UUID.randomUUID(), id, "OrderCashRefunded.v1", id.toString(), refundId.toString());
        return find(id);
    }

    private record Existing(Snapshot snapshot, String hash) {
    }
}
