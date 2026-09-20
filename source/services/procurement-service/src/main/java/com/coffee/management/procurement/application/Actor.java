package com.coffee.management.procurement.application;
import java.util.Set;
import java.util.UUID;
public record Actor(UUID userId, Set<String> permissions, Set<UUID> branchScopes, boolean globalScope) {
    public void require(String permission, UUID branchId) {
        if (!permissions.contains(permission) || (branchId != null && !globalScope && !branchScopes.contains(branchId)))
            throw new SecurityException("Permission or branch scope denied");
    }
}
