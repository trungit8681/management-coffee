package com.coffee.management.procurement.domain;
import java.util.UUID;
public record PurchaseOrder(UUID id,UUID branchId,UUID supplierId,UUID ingredientId,long quantity,long unitCostVnd,String status) {
    public PurchaseOrder {
        if (id==null || branchId==null || supplierId==null || ingredientId==null || quantity<=0 || unitCostVnd<0 || !"PENDING".equals(status))
            throw new IllegalArgumentException("Invalid purchase order");
        Math.multiplyExact(quantity,unitCostVnd);
    }
    public long totalCostVnd() { return Math.multiplyExact(quantity,unitCostVnd); }
}
