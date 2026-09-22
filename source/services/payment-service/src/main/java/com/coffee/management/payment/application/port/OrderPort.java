package com.coffee.management.payment.application.port;

import java.util.UUID;

public interface OrderPort {
    record CashQuote(UUID orderId, UUID branchId, long totalVnd, String status) {
    }

    record CancellationQuote(UUID orderId, UUID branchId, long totalVnd, String status) {
    }

    CashQuote quote(UUID orderId, String bearerToken);

    CancellationQuote cancellation(UUID orderId, String bearerToken);

    void confirm(UUID orderId, UUID paymentId, String bearerToken);

    void completeRefund(UUID orderId, UUID refundId, String bearerToken);
}