package com.coffee.management.order.application;

import com.coffee.management.order.application.port.*;
import com.coffee.management.order.domain.Order;
import com.coffee.management.order.infrastructure.persistence.JdbcOrderRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderApplicationService {
    public record RequestedItem(UUID variantId, int quantity) {}
    public record Create(UUID branchId, String channel, List<RequestedItem> items) {}
    private final CatalogPort catalog;
    private final PaymentPort payments;
    private final JdbcOrderRepository repository;
    private final TransactionTemplate transactions;
    public OrderApplicationService(CatalogPort catalog, PaymentPort payments, JdbcOrderRepository repository, TransactionTemplate transactions) {
        this.catalog=catalog; this.payments=payments; this.repository=repository; this.transactions=transactions;
    }
    public JdbcOrderRepository.Snapshot create(Create request, String key, Actor actor, String bearer) {
        if (key == null || key.isBlank() || key.length() > 128 || request.branchId() == null || request.items() == null || request.items().isEmpty())
            throw new OrderException(400, "INVALID_ORDER", "Branch, items and Idempotency-Key are required");
        actor.require("order:create", request.branchId());
        if (!List.of("POS","PICKUP","DELIVERY").contains(request.channel())) throw new OrderException(400,"INVALID_CHANNEL","Invalid channel");
        for (var item : request.items()) if (item.variantId() == null || item.quantity() <= 0 || item.quantity() > 100)
            throw new OrderException(400,"INVALID_ITEM","Invalid order item");
        String hash = hash(request, actor.userId());
        var existing = repository.byKey(key, hash);
        if (existing != null) return existing;
        List<JdbcOrderRepository.PricedItem> priced = new ArrayList<>();
        for (var requested : request.items()) {
            var offer = catalog.sellable(requested.variantId(), request.branchId(), request.channel(), bearer);
            if (!offer.sellable() || !requested.variantId().equals(offer.variantId()) || !request.branchId().equals(offer.branchId())
                    || !request.channel().equals(offer.channel()) || offer.priceVersion() <= 0 || offer.unitPriceVnd() < 0)
                throw new OrderException(409, "ITEM_NOT_SELLABLE", "Item unavailable or price changed");
            var line = new Order.Item(requested.variantId(), requested.quantity(), offer.unitPriceVnd(),
                    Math.multiplyExact(requested.quantity(), offer.unitPriceVnd()));
            priced.add(new JdbcOrderRepository.PricedItem(line, offer.priceVersion()));
        }
        long total = priced.stream().mapToLong(p -> p.item().lineTotalVnd()).reduce(0, Math::addExact);
        var order = new Order(UUID.randomUUID(), request.branchId(), request.channel(), priced.stream().map(JdbcOrderRepository.PricedItem::item).toList(), total,
                "POS".equals(request.channel()) ? "AWAITING_CASH" : "CONFIRMED");
        return transactions.execute(status -> repository.create(order, priced, actor.userId(), key, hash));
    }
    public JdbcOrderRepository.Snapshot get(UUID id, Actor actor) {
        var order = repository.find(id);
        if (order == null) throw new OrderException(404,"ORDER_NOT_FOUND","Order not found");
        actor.require("order:view", order.branchId());
        return order;
    }
    public JdbcOrderRepository.Snapshot cashQuote(UUID id, Actor actor) {
        var order = repository.find(id);
        if (order == null) throw new OrderException(404,"ORDER_NOT_FOUND","Order not found");
        actor.require("order:collect_cash", order.branchId());
        if (!"PENDING_CASH".equals(order.paymentStatus()) || "CANCELLED".equals(order.status()))
            throw new OrderException(409,"ORDER_NOT_AWAITING_CASH","Order is not awaiting cash");
        return order;
    }
    public JdbcOrderRepository.Snapshot confirm(UUID id, UUID paymentId, Actor actor, String bearer) {
        var order = repository.find(id);
        if (order == null) throw new OrderException(404,"ORDER_NOT_FOUND","Order not found");
        actor.require("order:collect_cash", order.branchId());
        if (paymentId.equals(order.paymentId()) && "PAID".equals(order.paymentStatus())) return order;
        if (!"PENDING_CASH".equals(order.paymentStatus()) || "CANCELLED".equals(order.status()))
            throw new OrderException(409,"ORDER_NOT_AWAITING_CASH","Order is not awaiting cash");
        var receipt = payments.receipt(id, bearer);
        if (!paymentId.equals(receipt.id()) || !id.equals(receipt.orderId()) || !order.branchId().equals(receipt.branchId())
                || order.totalVnd() != receipt.totalVnd() || receipt.receivedVnd() < receipt.totalVnd())
            throw new OrderException(409,"PAYMENT_MISMATCH","Payment does not match order");
        return transactions.execute(status -> repository.confirm(id, paymentId, receipt.totalVnd(), receipt.branchId()));
    }
    public JdbcOrderRepository.Snapshot cancel(UUID id, long version, String reason, Actor actor) {
        var order = repository.find(id);
        if (order == null) throw new OrderException(404,"ORDER_NOT_FOUND","Order not found");
        actor.require("PAID".equals(order.paymentStatus()) ? "order:approve_cancel" : "order:cancel", order.branchId());
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new OrderException(400,"INVALID_REASON","Cancellation reason required");
        return transactions.execute(status -> repository.cancel(id, version, reason, actor.userId()));
    }
    public JdbcOrderRepository.Snapshot completeRefund(UUID id, UUID refundId, Actor actor, String bearer) {
        var order = repository.find(id);
        if (order == null) throw new OrderException(404,"ORDER_NOT_FOUND","Order not found");
        actor.require("order:approve_cancel", order.branchId());
        var refund = payments.refund(id, bearer);
        if (!refundId.equals(refund.id()) || !id.equals(refund.orderId()) || !order.branchId().equals(refund.branchId()) || order.totalVnd() != refund.amountVnd())
            throw new OrderException(409,"REFUND_MISMATCH","Refund does not match order");
        return transactions.execute(status -> repository.completeRefund(id,refundId,refund.branchId(),refund.amountVnd()));
    }
    private static String hash(Create request, UUID actorId) {
        try {
            StringBuilder input = new StringBuilder(actorId+":"+request.branchId()+":"+request.channel());
            for (var item : request.items()) input.append(':').append(item.variantId()).append(':').append(item.quantity());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
