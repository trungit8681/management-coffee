package com.coffee.management.payment.domain;
import java.util.UUID;
public record CashRefund(UUID orderId,UUID branchId,long amountVnd,String reason) {
    public CashRefund {
        if (orderId==null || branchId==null || amountVnd<=0 || reason==null || reason.isBlank() || reason.length()>500)
            throw new IllegalArgumentException("Invalid cash refund");
    }
}
