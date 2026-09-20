package com.coffee.management.inventory.domain;

import java.util.UUID;

public record StockMovement(UUID branchId, UUID ingredientId, long quantity, String kind, UUID referenceId) {
    public StockMovement {
        if (branchId == null || ingredientId == null || quantity <= 0 || referenceId == null
                || !"IN".equals(kind) && !"OUT".equals(kind))
            throw new IllegalArgumentException("Invalid stock movement");
    }

    public long signedQuantity() {
        return "IN".equals(kind) ? quantity : -quantity;
    }
}
