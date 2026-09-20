package com.coffee.management.organization.application;

import java.util.Set;
import java.util.UUID;

public record Actor(UUID userId, Set<String> permissions, Set<UUID> branchScopes, boolean globalScope) {
    public void require(String permission, UUID branchId) {
        if (!permissions.contains(permission))
            throw new OrganizationException("FORBIDDEN", "Permission denied", 403);
        if (branchId != null && !globalScope && !branchScopes.contains(branchId))
            throw new OrganizationException("BRANCH_SCOPE_DENIED", "Branch is outside the actor scope", 403);
    }
}
