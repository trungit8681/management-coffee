package com.coffee.management.procurement.interfaces.rest;
import com.coffee.management.procurement.application.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/procurement")
public class ProcurementController {
    private final ProcurementService service;
    public ProcurementController(ProcurementService service) { this.service=service; }
    public record SupplierRequest(@NotBlank String code,@NotBlank String name) {}
    public record PurchaseRequest(@NotNull UUID branchId,@NotNull UUID supplierId,@NotNull UUID ingredientId,@Min(1) long quantity,@Min(0) long unitCostVnd) {}
    public record IdResult(UUID id) {}
    @PostMapping("/suppliers") @ResponseStatus(HttpStatus.CREATED)
    public IdResult supplier(@Valid @RequestBody SupplierRequest body,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor) {
        return new IdResult(service.supplier(body.code(),body.name(),key,actor));
    }
    @PostMapping("/purchase-orders") @ResponseStatus(HttpStatus.CREATED)
    public IdResult create(@Valid @RequestBody PurchaseRequest body,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor) {
        return new IdResult(service.create(body.branchId(),body.supplierId(),body.ingredientId(),body.quantity(),body.unitCostVnd(),key,actor));
    }
    @PostMapping("/purchase-orders/{id}/approve")
    public ProcurementService.PurchaseSnapshot approve(@PathVariable UUID id,@RequestHeader("If-Match") long version,@AuthenticationPrincipal Actor actor) {
        return service.approve(id,version,actor);
    }
    @GetMapping("/purchase-orders/{id}")
    public ProcurementService.PurchaseSnapshot get(@PathVariable UUID id,@AuthenticationPrincipal Actor actor) { return service.get(id,actor); }
}
