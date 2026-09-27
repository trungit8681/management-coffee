package com.coffee.management.order.application.port;

import java.util.UUID;

public interface CheckoutPort {
    record Benefit(UUID id, long discountVnd, String status) {}
    record Payment(UUID id, boolean orderConfirmed) {}
    Benefit reserveVoucher(UUID orderId, UUID branchId, String code, long totalVnd, String bearer);
    void finishVoucher(UUID orderId, boolean commit, String bearer);
    Benefit reservePoints(UUID orderId, UUID customerId, long points, String bearer);
    void finishPoints(UUID orderId, boolean commit, String bearer);
    UUID deduct(UUID orderId, UUID branchId, UUID ingredientId, long quantity, String key, String bearer);
    UUID reverse(UUID orderId, UUID branchId, UUID ingredientId, long quantity, String key, String bearer);
    Payment collect(UUID orderId, long receivedVnd, String key, String bearer);
}

