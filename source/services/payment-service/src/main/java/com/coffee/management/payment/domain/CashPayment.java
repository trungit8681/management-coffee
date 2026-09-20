package com.coffee.management.payment.domain;

import java.util.UUID;

public record CashPayment(UUID orderId, UUID branchId, long totalVnd, long receivedVnd) {
    public CashPayment {
        if (orderId == null || branchId == null || totalVnd <= 0 || receivedVnd < totalVnd)
            throw new IllegalArgumentException("Cash received must cover a positive order total");
    }
    public long changeVnd() { return receivedVnd - totalVnd; }
}
