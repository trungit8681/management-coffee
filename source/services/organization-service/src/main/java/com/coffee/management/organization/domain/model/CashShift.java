package com.coffee.management.organization.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CashShift(UUID id, UUID branchId, String registerCode, UUID openedBy, Instant openedAt,
        BigDecimal openingCash, Status status, long version) {
    public enum Status {
        OPEN, CLOSED
    }

    public CashShift {
        if (branchId == null || openedBy == null || openedAt == null || status == null)
            throw new IllegalArgumentException("Required cash shift field missing");
        if (registerCode == null || registerCode.isBlank())
            throw new IllegalArgumentException("registerCode is required");
        if (openingCash == null || openingCash.signum() < 0)
            throw new IllegalArgumentException("openingCash cannot be negative");
    }

    public BigDecimal expectedCash(BigDecimal sales, BigDecimal cashIn, BigDecimal cashOut, BigDecimal refunds) {
        requireNonNegative(sales, cashIn, cashOut, refunds);
        return openingCash.add(sales).add(cashIn).subtract(cashOut).subtract(refunds);
    }

    private static void requireNonNegative(BigDecimal... values) {
        for (BigDecimal value : values)
            if (value == null || value.signum() < 0)
                throw new IllegalArgumentException("Cash totals cannot be negative");
    }
}
