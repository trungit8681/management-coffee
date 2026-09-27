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
    public record RequestedItem(UUID variantId, int quantity) {
    }

    public record Create(UUID branchId, String channel, List<RequestedItem> items) {
    }

    private final CatalogPort catalog;
    private final PaymentPort payments;
    private final CheckoutPort checkout;
    private final JdbcOrderRepository repository;
    private final TransactionTemplate transactions;

    public OrderApplicationService(CatalogPort catalog, PaymentPort payments, CheckoutPort checkout,
            JdbcOrderRepository repository,
            TransactionTemplate transactions) {
        this.catalog = catalog;
        this.payments = payments;
        this.checkout = checkout;
        this.repository = repository;
        this.transactions = transactions;
    }

    public record Checkout(UUID customerId, long points, String voucherCode, long cashReceivedVnd) {
    }

    public record CheckoutResult(UUID orderId, String status, UUID paymentId, JdbcOrderRepository.Snapshot order) {
    }

    public CheckoutResult checkoutCash(UUID id, Checkout request, String key, Actor actor, String bearer) {
        var order = repository.find(id);
        if (order == null)
            throw new OrderException(404, "ORDER_NOT_FOUND", "Order not found");
        actor.require("order:collect_cash", order.branchId());
        if (key == null || key.isBlank() || key.length() > 128 || request.cashReceivedVnd() <= 0 || request.points() < 0
                || (request.points() > 0 && request.customerId() == null))
            throw new OrderException(400, "INVALID_CHECKOUT", "Invalid checkout request");
        String fingerprint = checkoutHash(id, request, actor.userId());
        var saga = transactions.execute(tx -> repository.startSaga(id, key, fingerprint, request.voucherCode(),
                request.customerId(), request.points(), request.cashReceivedVnd()));
        if ("COMPLETED".equals(saga.status()))
            return new CheckoutResult(id, saga.status(), saga.paymentId(), repository.find(id));
        if ("FAILED".equals(saga.status()))
            throw new OrderException(409, "CHECKOUT_FAILED", "Checkout was compensated; use a new order");
        UUID runner = UUID.randomUUID();
        if (!transactions.execute(tx -> repository.acquireSaga(id, runner)))
            return new CheckoutResult(id, "IN_PROGRESS", saga.paymentId(), repository.find(id));
        boolean paymentStarted = "PAID_PENDING".equals(saga.status());
        try {
            long voucherDiscount = 0, pointsDiscount = request.points();
            if (request.voucherCode() != null && !request.voucherCode().isBlank()) {
                var benefit = checkout.reserveVoucher(id, order.branchId(), request.voucherCode(), order.totalVnd(),
                        bearer);
                voucherDiscount = benefit.discountVnd();
                transactions.executeWithoutResult(
                        tx -> repository.sagaStep(id, "VOUCHER", benefit.id(), benefit.discountVnd()));
            }
            if (request.points() > 0) {
                var benefit = checkout.reservePoints(id, request.customerId(), request.points(), bearer);
                transactions
                        .executeWithoutResult(tx -> repository.sagaStep(id, "POINTS", benefit.id(), request.points()));
            }
            final long vd = voucherDiscount, pd = pointsDiscount;
            order = transactions.execute(tx -> {
                var updated = repository.applyDiscount(id, vd, pd);
                repository.sagaStatus(id, "BENEFITS_RESERVED", null, null);
                return updated;
            });
            var done = repository.sagaSteps(id, "STOCK").stream().map(JdbcOrderRepository.SagaStep::resourceId)
                    .collect(java.util.stream.Collectors.toSet());
            for (var stock : repository.requiredStock(id).entrySet())
                if (!done.contains(stock.getKey())) {
                    checkout.deduct(id, order.branchId(), stock.getKey(), stock.getValue(),
                            key + ":stock:" + stock.getKey(), bearer);
                    UUID ingredient = stock.getKey();
                    long quantity = stock.getValue();
                    transactions.executeWithoutResult(tx -> repository.sagaStep(id, "STOCK", ingredient, quantity));
                }
            transactions.executeWithoutResult(tx -> repository.sagaStatus(id, "STOCK_DEDUCTED", null, null));
            paymentStarted = true;
            var paid = checkout.collect(id, request.cashReceivedVnd(), key + ":payment", bearer);
            UUID paymentId = paid.id();
            transactions.executeWithoutResult(tx -> repository.sagaStatus(id, "PAID_PENDING", paymentId, null));
            if (request.voucherCode() != null && !request.voucherCode().isBlank())
                checkout.finishVoucher(id, true, bearer);
            if (request.points() > 0)
                checkout.finishPoints(id, true, bearer);
            transactions.executeWithoutResult(tx -> repository.sagaStatus(id, "COMPLETED", paymentId, null));
            return new CheckoutResult(id, "COMPLETED", paymentId, repository.find(id));
        } catch (RuntimeException failure) {
            if (paymentStarted) {
                transactions.executeWithoutResult(
                        tx -> repository.sagaStatus(id, "PAID_PENDING", null, failure.getMessage()));
                throw new OrderException(202, "CHECKOUT_FINALIZATION_PENDING",
                        "Cash may be recorded; retry with the same Idempotency-Key");
            }
            compensate(id, order.branchId(), request, bearer, key, failure);
            throw failure;
        } finally {
            transactions.executeWithoutResult(tx -> repository.releaseSaga(id, runner));
        }
    }

    private void compensate(UUID orderId, UUID branchId, Checkout request, String bearer, String key,
            RuntimeException cause) {
        transactions
                .executeWithoutResult(tx -> repository.sagaStatus(orderId, "COMPENSATING", null, cause.getMessage()));
        for (var step : repository.sagaSteps(orderId, "STOCK"))
            if ("DONE".equals(step.status())) {
                checkout.reverse(orderId, branchId, step.resourceId(), step.quantity(),
                        key + ":reverse:" + step.resourceId(), bearer);
                transactions.executeWithoutResult(tx -> repository.compensated(orderId, "STOCK", step.resourceId()));
            }
        if (request.points() > 0 && !repository.sagaSteps(orderId, "POINTS").isEmpty())
            checkout.finishPoints(orderId, false, bearer);
        if (request.voucherCode() != null && !request.voucherCode().isBlank()
                && !repository.sagaSteps(orderId, "VOUCHER").isEmpty())
            checkout.finishVoucher(orderId, false, bearer);
        transactions.executeWithoutResult(tx -> {
            repository.clearDiscount(orderId);
            repository.sagaStatus(orderId, "FAILED", null, cause.getMessage());
        });
    }

    public JdbcOrderRepository.Snapshot create(Create request, String key, Actor actor, String bearer) {
        if (key == null || key.isBlank() || key.length() > 128 || request.branchId() == null || request.items() == null
                || request.items().isEmpty())
            throw new OrderException(400, "INVALID_ORDER", "Branch, items and Idempotency-Key are required");
        actor.require("order:create", request.branchId());
        if (!List.of("POS", "PICKUP", "DELIVERY").contains(request.channel()))
            throw new OrderException(400, "INVALID_CHANNEL", "Invalid channel");
        for (var item : request.items())
            if (item.variantId() == null || item.quantity() <= 0 || item.quantity() > 100)
                throw new OrderException(400, "INVALID_ITEM", "Invalid order item");
        String hash = hash(request, actor.userId());
        var existing = repository.byKey(key, hash);
        if (existing != null)
            return existing;
        List<JdbcOrderRepository.PricedItem> priced = new ArrayList<>();
        for (var requested : request.items()) {
            var offer = catalog.sellable(requested.variantId(), request.branchId(), request.channel(), bearer);
            if (!offer.sellable() || !requested.variantId().equals(offer.variantId())
                    || !request.branchId().equals(offer.branchId())
                    || !request.channel().equals(offer.channel()) || offer.priceVersion() <= 0
                    || offer.unitPriceVnd() < 0)
                throw new OrderException(409, "ITEM_NOT_SELLABLE", "Item unavailable or price changed");
            var line = new Order.Item(requested.variantId(), requested.quantity(), offer.unitPriceVnd(),
                    Math.multiplyExact(requested.quantity(), offer.unitPriceVnd()));
            if (offer.recipeVersion() <= 0 || offer.recipe() == null || offer.recipe().isEmpty())
                throw new OrderException(409, "RECIPE_NOT_PUBLISHED", "Item recipe is not published");
            priced.add(new JdbcOrderRepository.PricedItem(line, offer.priceVersion(), offer.recipeVersion(),
                    offer.recipe().stream()
                            .map(i -> new JdbcOrderRepository.RecipeIngredient(i.ingredientId(), i.quantity()))
                            .toList()));
        }
        long total = priced.stream().mapToLong(p -> p.item().lineTotalVnd()).reduce(0, Math::addExact);
        var order = new Order(UUID.randomUUID(), request.branchId(), request.channel(),
                priced.stream().map(JdbcOrderRepository.PricedItem::item).toList(), total,
                "POS".equals(request.channel()) ? "AWAITING_CASH" : "CONFIRMED");
        return transactions.execute(status -> repository.create(order, priced, actor.userId(), key, hash));
    }

    public JdbcOrderRepository.Snapshot get(UUID id, Actor actor) {
        var order = repository.find(id);
        if (order == null)
            throw new OrderException(404, "ORDER_NOT_FOUND", "Order not found");
        actor.require("order:view", order.branchId());
        return order;
    }

    public JdbcOrderRepository.Snapshot cashQuote(UUID id, Actor actor) {
        var order = repository.find(id);
        if (order == null)
            throw new OrderException(404, "ORDER_NOT_FOUND", "Order not found");
        actor.require("order:collect_cash", order.branchId());
        if (!"PENDING_CASH".equals(order.paymentStatus()) || "CANCELLED".equals(order.status()))
            throw new OrderException(409, "ORDER_NOT_AWAITING_CASH", "Order is not awaiting cash");
        return order;
    }

    public JdbcOrderRepository.Snapshot confirm(UUID id, UUID paymentId, Actor actor, String bearer) {
        var order = repository.find(id);
        if (order == null)
            throw new OrderException(404, "ORDER_NOT_FOUND", "Order not found");
        actor.require("order:collect_cash", order.branchId());
        if (paymentId.equals(order.paymentId()) && "PAID".equals(order.paymentStatus()))
            return order;
        if (!"PENDING_CASH".equals(order.paymentStatus()) || "CANCELLED".equals(order.status()))
            throw new OrderException(409, "ORDER_NOT_AWAITING_CASH", "Order is not awaiting cash");
        var receipt = payments.receipt(id, bearer);
        if (!paymentId.equals(receipt.id()) || !id.equals(receipt.orderId())
                || !order.branchId().equals(receipt.branchId())
                || order.totalVnd() != receipt.totalVnd() || receipt.receivedVnd() < receipt.totalVnd())
            throw new OrderException(409, "PAYMENT_MISMATCH", "Payment does not match order");
        return transactions
                .execute(status -> repository.confirm(id, paymentId, receipt.totalVnd(), receipt.branchId()));
    }

    public JdbcOrderRepository.Snapshot cancel(UUID id, long version, String reason, Actor actor) {
        var order = repository.find(id);
        if (order == null)
            throw new OrderException(404, "ORDER_NOT_FOUND", "Order not found");
        actor.require("PAID".equals(order.paymentStatus()) ? "order:approve_cancel" : "order:cancel", order.branchId());
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new OrderException(400, "INVALID_REASON", "Cancellation reason required");
        return transactions.execute(status -> repository.cancel(id, version, reason, actor.userId()));
    }

    public JdbcOrderRepository.Snapshot completeRefund(UUID id, UUID refundId, Actor actor, String bearer) {
        var order = repository.find(id);
        if (order == null)
            throw new OrderException(404, "ORDER_NOT_FOUND", "Order not found");
        actor.require("order:approve_cancel", order.branchId());
        var refund = payments.refund(id, bearer);
        if (!refundId.equals(refund.id()) || !id.equals(refund.orderId()) || !order.branchId().equals(refund.branchId())
                || order.totalVnd() != refund.amountVnd())
            throw new OrderException(409, "REFUND_MISMATCH", "Refund does not match order");
        return transactions
                .execute(status -> repository.completeRefund(id, refundId, refund.branchId(), refund.amountVnd()));
    }

    private static String hash(Create request, UUID actorId) {
        try {
            StringBuilder input = new StringBuilder(actorId + ":" + request.branchId() + ":" + request.channel());
            for (var item : request.items())
                input.append(':').append(item.variantId()).append(':').append(item.quantity());
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String checkoutHash(UUID orderId, Checkout request, UUID actorId) {
        try {
            String input = actorId + ":" + orderId + ":" + request.customerId() + ":" + request.points() + ":"
                    + request.voucherCode() + ":" + request.cashReceivedVnd();
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
