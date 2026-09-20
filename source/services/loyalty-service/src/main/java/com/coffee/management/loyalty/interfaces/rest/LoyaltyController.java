package com.coffee.management.loyalty.interfaces.rest;
import com.coffee.management.loyalty.application.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/loyalty")
public class LoyaltyController {
    private final LoyaltyService service;
    public LoyaltyController(LoyaltyService service) { this.service=service; }
    public record CustomerRequest(UUID identityUserId) {}
    public record PointsRequest(@NotNull UUID customerId,@Min(1) long points,@NotNull UUID referenceId) {}
    public record ReserveRequest(@NotNull UUID customerId,@NotNull UUID orderId,@Min(1) long points) {}
    public record IdResult(UUID id) {}
    @PostMapping("/customers") @ResponseStatus(HttpStatus.CREATED)
    public IdResult customer(@Valid @RequestBody CustomerRequest body,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor) {
        return new IdResult(service.createCustomer(body.identityUserId(),key,actor));
    }
    @PostMapping("/credits") @ResponseStatus(HttpStatus.CREATED)
    public IdResult credit(@Valid @RequestBody PointsRequest body,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor) {
        return new IdResult(service.credit(body.customerId(),body.points(),body.referenceId(),key,actor));
    }
    @PostMapping("/reservations") @ResponseStatus(HttpStatus.CREATED)
    public LoyaltyService.Reservation reserve(@Valid @RequestBody ReserveRequest body,@AuthenticationPrincipal Actor actor) {
        return service.reserve(body.customerId(),body.orderId(),body.points(),actor);
    }
    @PostMapping("/reservations/{orderId}/commit")
    public LoyaltyService.Reservation commit(@PathVariable UUID orderId,@AuthenticationPrincipal Actor actor) { return service.commit(orderId,actor); }
    @PostMapping("/reservations/{orderId}/release")
    public LoyaltyService.Reservation release(@PathVariable UUID orderId,@AuthenticationPrincipal Actor actor) { return service.release(orderId,actor); }
    @GetMapping("/customers/{id}/wallet")
    public LoyaltyService.Wallet wallet(@PathVariable UUID id,@AuthenticationPrincipal Actor actor) { return service.wallet(id,actor); }
}
