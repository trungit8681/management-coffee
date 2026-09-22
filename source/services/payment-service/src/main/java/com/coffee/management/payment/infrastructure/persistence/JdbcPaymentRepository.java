package com.coffee.management.payment.infrastructure.persistence;

import com.coffee.management.payment.application.PaymentException;
import com.coffee.management.payment.domain.CashPayment;
import com.coffee.management.payment.domain.CashRefund;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentRepository {
    public record Receipt(UUID id, UUID orderId, UUID branchId, long totalVnd, long receivedVnd, long changeVnd) {
    }

    public record RefundReceipt(UUID id, UUID orderId, UUID branchId, long amountVnd) {
    }

    private final JdbcTemplate db;

    public JdbcPaymentRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Receipt record(CashPayment payment, String key, String hash, UUID actorId) {
        var existing = findByKey(key);
        if (existing != null) {
            if (!existing.hash().equals(hash))
                throw new PaymentException(409, "IDEMPOTENCY_CONFLICT", "Key used for another payment");
            return existing.receipt();
        }
        UUID id = UUID.randomUUID();
        int inserted = db.update(
                "INSERT INTO cash_payment(id,order_id,branch_id,amount_due_vnd,cash_received_vnd,actor_id,idempotency_key,request_hash) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                id, payment.orderId(), payment.branchId(), payment.totalVnd(), payment.receivedVnd(), actorId, key,
                hash);
        if (inserted == 0) {
            existing = findByKey(key);
            if (existing != null && existing.hash().equals(hash))
                return existing.receipt();
            throw new PaymentException(409, "PAYMENT_ALREADY_RECORDED",
                    "Order already has a cash payment or key conflicts");
        }
        db.update(
                "INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES (?,?,?,jsonb_build_object('paymentId',?::text,'orderId',?::text,'branchId',?::text,'totalVnd',?::bigint))",
                UUID.randomUUID(), id, "CashPaymentRecorded.v1", id.toString(), payment.orderId().toString(),
                payment.branchId().toString(), payment.totalVnd());
        return new Receipt(id, payment.orderId(), payment.branchId(), payment.totalVnd(), payment.receivedVnd(),
                payment.changeVnd());
    }

    public Receipt findByOrder(UUID orderId) {
        var rows = db.query(
                "SELECT id,order_id,branch_id,amount_due_vnd,cash_received_vnd,change_vnd FROM cash_payment WHERE order_id=?",
                (rs, n) -> new Receipt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getLong(4), rs.getLong(5), rs.getLong(6)),
                orderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Receipt replay(String key, UUID orderId, long receivedVnd, UUID actorId) {
        var existing = findByKey(key);
        if (existing == null)
            return null;
        Receipt receipt = existing.receipt();
        var payment = new CashPayment(receipt.orderId(), receipt.branchId(), receipt.totalVnd(), receipt.receivedVnd());
        String expected = com.coffee.management.payment.application.PaymentApplicationService.hash(payment, actorId);
        if (!receipt.orderId().equals(orderId) || receipt.receivedVnd() != receivedVnd
                || !existing.hash().equals(expected))
            throw new PaymentException(409, "IDEMPOTENCY_CONFLICT", "Key used for another payment");
        return receipt;
    }

    public RefundReceipt findRefundByOrder(UUID orderId) {
        var rows = db.query(
                "SELECT r.id,p.order_id,p.branch_id,r.amount_vnd FROM cash_refund r JOIN cash_payment p ON p.id=r.payment_id WHERE p.order_id=?",
                (rs, n) -> new RefundReceipt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getLong(4)),
                orderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public RefundReceipt replayRefund(String key, UUID orderId, String reason, UUID actorId) {
        var rows = db.query(
                "SELECT r.id,p.order_id,p.branch_id,r.amount_vnd,r.request_hash FROM cash_refund r JOIN cash_payment p ON p.id=r.payment_id WHERE r.idempotency_key=?",
                (rs, n) -> new RefundExisting(new RefundReceipt(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getLong(4)), rs.getString(5)),
                key);
        if (rows.isEmpty())
            return null;
        var prior = rows.getFirst();
        var r = prior.receipt();
        String expected = com.coffee.management.payment.application.PaymentApplicationService
                .refundHash(new CashRefund(r.orderId(), r.branchId(), r.amountVnd(), reason), actorId);
        if (!r.orderId().equals(orderId) || !prior.hash().equals(expected))
            throw new PaymentException(409, "IDEMPOTENCY_CONFLICT", "Refund key conflicts");
        return r;
    }

    public RefundReceipt recordRefund(CashRefund refund, String key, String hash, UUID actorId) {
        var existing = db.query(
                "SELECT r.id,p.order_id,p.branch_id,r.amount_vnd,r.request_hash FROM cash_refund r JOIN cash_payment p ON p.id=r.payment_id WHERE r.idempotency_key=?",
                (rs, n) -> new RefundExisting(new RefundReceipt(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getLong(4)), rs.getString(5)),
                key);
        if (!existing.isEmpty()) {
            var prior = existing.getFirst();
            if (!prior.hash().equals(hash))
                throw new PaymentException(409, "IDEMPOTENCY_CONFLICT", "Refund key conflicts");
            return prior.receipt();
        }
        var payments = db.query("SELECT id,branch_id,amount_due_vnd FROM cash_payment WHERE order_id=? FOR UPDATE",
                (rs, n) -> new PaymentRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)),
                refund.orderId());
        if (payments.isEmpty())
            throw new PaymentException(404, "PAYMENT_NOT_FOUND", "Cash payment not found");
        var payment = payments.getFirst();
        if (!payment.branchId().equals(refund.branchId()) || payment.totalVnd() != refund.amountVnd())
            throw new PaymentException(409, "REFUND_MISMATCH", "Refund must cover the cash order total");
        if (findRefundByOrder(refund.orderId()) != null)
            throw new PaymentException(409, "ALREADY_REFUNDED", "Cash payment already refunded");
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO cash_refund(id,payment_id,amount_vnd,reason,approved_by,idempotency_key,request_hash) VALUES (?,?,?,?,?,?,?)",
                id, payment.id(), refund.amountVnd(), refund.reason(), actorId, key, hash);
        db.update(
                "INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES (?,?,?,jsonb_build_object('refundId',?::text,'orderId',?::text,'amountVnd',?::bigint))",
                UUID.randomUUID(), id, "CashRefundRecorded.v1", id.toString(), refund.orderId().toString(),
                refund.amountVnd());
        return new RefundReceipt(id, refund.orderId(), refund.branchId(), refund.amountVnd());
    }

    private record PaymentRow(UUID id, UUID branchId, long totalVnd) {
    }

    private record RefundExisting(RefundReceipt receipt, String hash) {
    }

    private Existing findByKey(String key) {
        var rows = db.query(
                "SELECT id,order_id,branch_id,amount_due_vnd,cash_received_vnd,change_vnd,request_hash FROM cash_payment WHERE idempotency_key=?",
                (rs, n) -> new Existing(
                        new Receipt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                rs.getObject(3, UUID.class), rs.getLong(4), rs.getLong(5), rs.getLong(6)),
                        rs.getString(7)),
                key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private record Existing(Receipt receipt, String hash) {
    }
}
