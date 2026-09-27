package com.coffee.management.order.application.port;
import java.util.UUID;
import java.util.List;
public interface CatalogPort {
    record RecipeIngredient(UUID ingredientId, long quantity) {}
    record Sellable(UUID variantId, UUID branchId, String channel, long unitPriceVnd, long priceVersion,
            boolean sellable, long recipeVersion, List<RecipeIngredient> recipe) {}
    Sellable sellable(UUID variantId, UUID branchId, String channel, String bearer);
}
