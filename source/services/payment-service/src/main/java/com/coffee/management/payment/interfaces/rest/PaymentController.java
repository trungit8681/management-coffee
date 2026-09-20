package com.coffee.management.payment.interfaces.rest;

import com.coffee.management.payment.application.*;
import com.coffee.management.payment.application.port.OrderPort;
import com.coffee.management.payment.infrastructure.persistence.JdbcPaymentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentApplicationService service;
    private final JdbcPaymentRepository repository;
    private final OrderPort orders;

    public PaymentController(PaymentApplicationService service, JdbcPaymentRepository repository, OrderPort orders) {
        this.service = service;
        this.repository = repository;
        this.orders = orders;
    }

    public record CashRequest(@NotNull UUID orderId, @Min(1) long cashReceivedVnd) {
    }

    public record CashResult(JdbcPaymentRepository.Receipt receipt, boolean orderConfirmed) {
    }

    public record RefundRequest(@NotNull UUID orderId, @NotBlank String reason) {
    }

    public record RefundResult(JdbcPaymentRepository.RefundReceipt refund, boolean orderRefunded) {
    }

    @PostMapping("/cash")
    public ResponseEntity<CashResult> collect(@Valid @RequestBody CashRequest body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("Authorization") String bearer, @AuthenticationPrincipal Actor actor) {
        var receipt = service.collect(body.orderId(), body.cashReceivedVnd(), key, actor, bearer);
        try {
            orders.confirm(receipt.orderId(), receipt.id(), bearer);
            return ResponseEntity.status(HttpStatus.CREATED).body(new CashResult(receipt, true));
        } catch (PaymentException ex) {
            if (!"ORDER_CONFIRM_PENDING".equals(ex.code()))
                throw ex;
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new CashResult(receipt, false));
        }
    }

    @GetMapping("/cash/by-order/{orderId}")
    public JdbcPaymentRepository.Receipt receipt(@PathVariable UUID orderId, @AuthenticationPrincipal Actor actor) {
        var receipt = repository.findByOrder(orderId);
        if (receipt == null)
            throw new PaymentException(404, "PAYMENT_NOT_FOUND", "Payment not found");
        actor.require("payment:view_cash", receipt.branchId());
        return receipt;
    }

    @PostMapping("/cash/refunds")
    public ResponseEntity<RefundResult> refund(@Valid @RequestBody RefundRequest body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("Authorization") String bearer, @AuthenticationPrincipal Actor actor) {
        var refund = service.refund(body.orderId(), body.reason(), key, actor, bearer);
        try {
            orders.completeRefund(refund.orderId(), refund.id(), bearer);
            return ResponseEntity.status(HttpStatus.CREATED).body(new RefundResult(refund, true));
        } catch (PaymentException ex) {
            if (!"ORDER_REFUND_PENDING".equals(ex.code()))
                throw ex;
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RefundResult(refund, false));
        }
    }

    @GetMapping("/cash/refunds/by-order/{orderId}")
    public JdbcPaymentRepository.RefundReceipt refundReceipt(@PathVariable UUID orderId,
            @AuthenticationPrincipal Actor actor) {
        var refund = repository.findRefundByOrder(orderId);
        if (refund == null)
            throw new PaymentException(404, "REFUND_NOT_FOUND", "Refund not found");
        actor.require("payment:view_cash", refund.branchId());
        return refund;
    }
}
