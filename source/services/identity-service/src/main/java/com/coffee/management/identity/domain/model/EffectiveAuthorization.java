package com.coffee.management.identity.domain.model;

import java.util.Set;
import java.util.UUID;

public record EffectiveAuthorization(Set<String> permissions, Set<UUID> branchScopes, boolean globalScope) {
    public EffectiveAuthorization {
        permissions = Set.copyOf(permissions);
        branchScopes = Set.copyOf(branchScopes);
    }

    public boolean allows(String permission, UUID branchId) {
        return permissions.contains(permission)
                && (branchId == null ? globalScope : globalScope || branchScopes.contains(branchId));
    }
}
