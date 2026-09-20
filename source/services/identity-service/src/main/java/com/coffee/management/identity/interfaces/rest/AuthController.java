package com.coffee.management.identity.interfaces.rest;

import com.coffee.management.identity.application.AuthApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthApplicationService auth;

    public AuthController(AuthApplicationService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public AuthApplicationService.TokenPair login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        String correlation = Optional.ofNullable(request.getHeader("X-Correlation-Id"))
                .orElseGet(() -> UUID.randomUUID().toString());
        return auth.login(new AuthApplicationService.LoginCommand(body.identifier(), body.password(), body.deviceId(),
                body.deviceName(), request.getHeader("User-Agent"), clientIp(request), correlation, requestId));
    }

    @PostMapping("/token/refresh")
    public AuthApplicationService.TokenPair refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return auth.refresh(body.refreshToken(), correlation(request), requestId, clientIp(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest body, HttpServletRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        auth.logout(body.refreshToken(), correlation(request), requestId, clientIp(request));
        return ResponseEntity.noContent().build();
    }

    private String correlation(HttpServletRequest r) {
        return Optional.ofNullable(r.getHeader("X-Correlation-Id")).orElseGet(() -> UUID.randomUUID().toString());
    }

    private String clientIp(HttpServletRequest r) {
        return r.getRemoteAddr();
    }

    public record LoginRequest(@NotBlank String identifier, @NotBlank @Size(max = 200) String password,
            @NotBlank @Size(max = 200) String deviceId, @Size(max = 200) String deviceName) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }
}
