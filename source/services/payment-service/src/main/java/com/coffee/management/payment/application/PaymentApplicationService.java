package com.coffee.management.payment.application;

import com.coffee.management.payment.application.port.OrderPort;
import com.coffee.management.payment.domain.CashPayment;
import com.coffee.management.payment.domain.CashRefund;
import com.coffee.management.payment.infrastructure.persistence.JdbcPaymentRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentApplicationService {
    private final OrderPort orders;
    private final JdbcPaymentRepository repository;
    private final TransactionTemplate transactions;

    public PaymentApplicationService(OrderPort orders, JdbcPaymentRepository repository,
            TransactionTemplate transactions) {
        this.orders = orders;
        this.repository = repository;
        this.transactions = transactions;
    }

    public JdbcPaymentRepository.Receipt collect(UUID orderId, long receivedVnd, String key, Actor actor,
            String bearer) {
        if (key == null || key.isBlank() || key.length() > 128)
            throw new PaymentException(400, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key is required");
        var replay = repository.replay(key, orderId, receivedVnd, actor.userId());
        if (replay != null) {
            actor.require("payment:collect_cash", replay.branchId());
            return replay;
        }
        var quote = orders.quote(orderId, bearer);
        actor.require("payment:collect_cash", quote.branchId());
        if (!"AWAITING_CASH".equals(quote.status()) && !"PENDING_CASH".equals(quote.status()))
            throw new PaymentException(409, "ORDER_NOT_AWAITING_CASH", "Order is not awaiting cash");
        CashPayment payment = new CashPayment(orderId, quote.branchId(), quote.totalVnd(), receivedVnd);
        return transactions
                .execute(status -> repository.record(payment, key, hash(payment, actor.userId()), actor.userId()));
    }

    public JdbcPaymentRepository.RefundReceipt refund(UUID orderId, String reason, String key, Actor actor,
            String bearer) {
        if (key == null || key.isBlank() || key.length() > 128)
            throw new PaymentException(400, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key required");
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new PaymentException(400, "INVALID_REASON", "Refund reason required");
        var replay = repository.replayRefund(key, orderId, reason, actor.userId());
        if (replay != null) {
            actor.require("payment:refund_cash", replay.branchId());
            return replay;
        }
        var order = orders.cancellation(orderId, bearer);
        actor.require("payment:refund_cash", order.branchId());
        if (!"REFUND_PENDING".equals(order.status()) && !"CANCELLED".equals(order.status()))
            throw new PaymentException(409, "ORDER_NOT_AWAITING_REFUND", "Order is not approved for refund");
        var refund = new CashRefund(orderId, order.branchId(), order.totalVnd(), reason);
        return transactions.execute(
                status -> repository.recordRefund(refund, key, refundHash(refund, actor.userId()), actor.userId()));
    }

    public static String refundHash(CashRefund refund, UUID actorId) {
        try {
            String input = refund.orderId() + ":" + refund.branchId() + ":" + refund.amountVnd() + ":" + refund.reason()
                    + ":" + actorId;
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String hash(CashPayment payment, UUID actorId) {
        try {
            String input = payment.orderId() + ":" + payment.branchId() + ":" + payment.totalVnd() + ":"
                    + payment.receivedVnd() + ":" + actorId;
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
