package com.coffee.management.order.application.port;
import java.util.UUID;
public interface PaymentPort {
    record Receipt(UUID id, UUID orderId, UUID branchId, long totalVnd, long receivedVnd, long changeVnd) {}
    record Refund(UUID id, UUID orderId, UUID branchId, long amountVnd) {}
    Receipt receipt(UUID orderId, String bearer);
    Refund refund(UUID orderId, String bearer);
}
