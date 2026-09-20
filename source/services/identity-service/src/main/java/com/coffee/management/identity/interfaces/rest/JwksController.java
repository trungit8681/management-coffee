package com.coffee.management.identity.interfaces.rest;

import com.coffee.management.identity.infrastructure.security.JwtService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class JwksController {
    private final JwtService jwt;

    public JwksController(JwtService jwt) {
        this.jwt = jwt;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return jwt.publicJwkSet();
    }
}
