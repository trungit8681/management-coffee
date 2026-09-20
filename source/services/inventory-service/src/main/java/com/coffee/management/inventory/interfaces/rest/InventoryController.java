package com.coffee.management.inventory.interfaces.rest;

import com.coffee.management.inventory.application.*;
import com.coffee.management.inventory.domain.StockMovement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {
    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    public record IngredientRequest(@NotBlank String code, @NotBlank String name, @NotBlank String unit) {
    }

    public record MoveRequest(@NotNull UUID branchId, @NotNull UUID ingredientId, @Min(1) long quantity,
            @NotNull UUID referenceId) {
    }

    public record IdResult(UUID id) {
    }

    public record BalanceResult(UUID branchId, UUID ingredientId, long quantity) {
    }

    @PostMapping("/ingredients")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResult ingredient(@Valid @RequestBody IngredientRequest body, @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return new IdResult(service.createIngredient(body.code(), body.name(), body.unit(), key, actor));
    }

    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResult receive(@Valid @RequestBody MoveRequest body, @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return new IdResult(service.move(
                new StockMovement(body.branchId(), body.ingredientId(), body.quantity(), "IN", body.referenceId()), key,
                actor));
    }

    @PostMapping("/deductions")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResult deduct(@Valid @RequestBody MoveRequest body, @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor actor) {
        return new IdResult(service.move(
                new StockMovement(body.branchId(), body.ingredientId(), body.quantity(), "OUT", body.referenceId()),
                key, actor));
    }

    @GetMapping("/branches/{branchId}/ingredients/{ingredientId}/balance")
    public BalanceResult balance(@PathVariable UUID branchId, @PathVariable UUID ingredientId,
            @AuthenticationPrincipal Actor actor) {
        return new BalanceResult(branchId, ingredientId, service.balance(branchId, ingredientId, actor));
    }
}
