package com.coffee.management.order.application.port;
import java.util.UUID;
public interface CatalogPort {
    record Sellable(UUID variantId, UUID branchId, String channel, long unitPriceVnd, long priceVersion, boolean sellable) {}
    Sellable sellable(UUID variantId, UUID branchId, String channel, String bearer);
}
