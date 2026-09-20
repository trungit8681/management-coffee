package com.coffee.management.identity.interfaces.rest;

import com.coffee.management.identity.application.IdentityAdminService;
import com.coffee.management.identity.domain.model.UserAccount;
import com.coffee.management.identity.infrastructure.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity")
public class IdentityController {
    private final IdentityAdminService service;

    public IdentityController(IdentityAdminService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public IdentityAdminService.Profile me(@AuthenticationPrincipal JwtService.AccessPrincipal actor) {
        return service.profile(actor);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('identity:create')")
    public IdResponse createUser(@Valid @RequestBody CreateUser body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal JwtService.AccessPrincipal actor,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String correlation) {
        return new IdResponse(service.createUser(body.username(), body.email(), body.password(), body.displayName(),
                idempotencyKey, actor.userId(), correlation));
    }

    @PatchMapping("/users/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('identity:change_status')")
    public void status(@PathVariable UUID id, @Valid @RequestBody StatusRequest body,
            @AuthenticationPrincipal JwtService.AccessPrincipal actor,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String correlation) {
        service.changeStatus(id, body.status(), actor.userId(), correlation);
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('identity:manage_roles')")
    public IdResponse createRole(@Valid @RequestBody CreateRole body,
            @AuthenticationPrincipal JwtService.AccessPrincipal actor,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String correlation) {
        return new IdResponse(
                service.createRole(body.code(), body.name(), body.description(), actor.userId(), correlation));
    }

    @PutMapping("/roles/{id}/permissions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('identity:manage_roles')")
    public void permissions(@PathVariable UUID id, @Valid @RequestBody PermissionRequest body,
            @AuthenticationPrincipal JwtService.AccessPrincipal actor,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String correlation) {
        service.replacePermissions(id, body.permissions(), actor.userId(), correlation);
    }

    @PostMapping("/users/{id}/role-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('identity:assign_role')")
    public IdResponse assign(@PathVariable UUID id, @Valid @RequestBody AssignRole body,
            @AuthenticationPrincipal JwtService.AccessPrincipal actor,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String correlation) {
        return new IdResponse(service.assignRole(id, body.roleId(), body.branchId(), actor, correlation));
    }

    public record CreateUser(@NotBlank @Size(max = 100) String username, @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 10, max = 200) String password, @NotBlank @Size(max = 160) String displayName) {
    }

    public record StatusRequest(UserAccount.Status status) {
    }

    public record CreateRole(@NotBlank @Size(max = 80) String code, @NotBlank @Size(max = 160) String name,
            @Size(max = 500) String description) {
    }

    public record PermissionRequest(@NotEmpty Set<String> permissions) {
    }

    public record AssignRole(UUID roleId, UUID branchId) {
    }

    public record IdResponse(UUID id) {
    }
}
