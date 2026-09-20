package com.coffee.management.order.interfaces.rest;

import com.coffee.management.order.application.*;
import com.coffee.management.order.infrastructure.persistence.JdbcOrderRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderApplicationService service;
    public OrderController(OrderApplicationService service) { this.service=service; }
    public record ItemRequest(@NotNull UUID variantId,@Min(1) @Max(100) int quantity) {}
    public record CreateRequest(@NotNull UUID branchId,@NotBlank String channel,@NotEmpty List<@Valid ItemRequest> items) {}
    public record ConfirmRequest(@NotNull UUID paymentId) {}
    public record CancelRequest(@NotBlank String reason) {}
    public record RefundRequest(@NotNull UUID refundId) {}
    public record CashQuote(UUID orderId,UUID branchId,long totalVnd,String status) {}
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JdbcOrderRepository.Snapshot create(@Valid @RequestBody CreateRequest body,
            @RequestHeader("Idempotency-Key") String key,@RequestHeader("Authorization") String bearer,
            @AuthenticationPrincipal Actor actor) {
        return service.create(new OrderApplicationService.Create(body.branchId(),body.channel(),
                body.items().stream().map(i -> new OrderApplicationService.RequestedItem(i.variantId(),i.quantity())).toList()),key,actor,bearer);
    }
    @GetMapping("/{id}")
    public JdbcOrderRepository.Snapshot get(@PathVariable UUID id,@AuthenticationPrincipal Actor actor) { return service.get(id,actor); }
    @GetMapping("/{id}/cash-quote")
    public CashQuote quote(@PathVariable UUID id,@AuthenticationPrincipal Actor actor) {
        var order=service.cashQuote(id,actor);
        return new CashQuote(order.id(),order.branchId(),order.totalVnd(),
                "AWAITING_CASH".equals(order.status()) ? "AWAITING_CASH" : "PENDING_CASH");
    }
    @PostMapping("/{id}/confirm-cash")
    public JdbcOrderRepository.Snapshot confirm(@PathVariable UUID id,@Valid @RequestBody ConfirmRequest body,
            @RequestHeader("Authorization") String bearer,@AuthenticationPrincipal Actor actor) {
        return service.confirm(id,body.paymentId(),actor,bearer);
    }
    @PostMapping("/{id}/cancel")
    public JdbcOrderRepository.Snapshot cancel(@PathVariable UUID id,@RequestHeader("If-Match") long version,
            @Valid @RequestBody CancelRequest body,@AuthenticationPrincipal Actor actor) {
        return service.cancel(id,version,body.reason(),actor);
    }
    @PostMapping("/{id}/complete-refund")
    public JdbcOrderRepository.Snapshot completeRefund(@PathVariable UUID id,@Valid @RequestBody RefundRequest body,
            @RequestHeader("Authorization") String bearer,@AuthenticationPrincipal Actor actor) {
        return service.completeRefund(id,body.refundId(),actor,bearer);
    }
}
