package com.coffee.management.order.domain;
import java.util.List;
import java.util.UUID;
public record Order(UUID id, UUID branchId, String channel, List<Item> items, long totalVnd, String status) {
    public record Item(UUID variantId, int quantity, long unitPriceVnd, long lineTotalVnd) {
        public Item {
            if (variantId == null || quantity <= 0 || unitPriceVnd < 0 || lineTotalVnd != Math.multiplyExact(quantity, unitPriceVnd))
                throw new IllegalArgumentException("Invalid order line");
        }
    }
    public Order {
        if (id == null || branchId == null || items == null || items.isEmpty() || !List.of("POS","PICKUP","DELIVERY").contains(channel)
                || totalVnd <= 0 || totalVnd != items.stream().mapToLong(Item::lineTotalVnd).sum())
            throw new IllegalArgumentException("Invalid order");
        items = List.copyOf(items);
    }
}
