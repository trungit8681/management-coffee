package com.coffee.management.loyalty.infrastructure.security;

import com.coffee.management.loyalty.application.Actor;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JwtVerifier {
    private final String issuer, audience;
    private final URI jwksUri;
    private final Duration cacheTtl;
    private final HttpClient http;
    private volatile CachedKeys cached = new CachedKeys(Map.of(), Instant.EPOCH);

    @Autowired
    public JwtVerifier(@Value("${loyalty.jwt.issuer}") String issuer,
            @Value("${loyalty.jwt.audience}") String audience,
            @Value("${loyalty.jwt.jwks-uri}") String jwksUri,
            @Value("${loyalty.jwt.jwks-cache-ttl:PT5M}") Duration cacheTtl) {
        this(issuer, audience, URI.create(jwksUri), cacheTtl,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    JwtVerifier(String issuer, String audience, URI jwksUri, Duration cacheTtl, HttpClient http) {
        this.issuer = issuer;
        this.audience = audience;
        this.jwksUri = jwksUri;
        this.cacheTtl = cacheTtl;
        this.http = http;
    }

    public Actor verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            String kid = jwt.getHeader().getKeyID();
            if (kid == null || kid.isBlank() || !JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()))
                throw new IllegalArgumentException();
            RSAKey key = key(kid, false);
            if (key == null)
                key = key(kid, true);
            var c = jwt.getJWTClaimsSet();
            if (key == null || !jwt.verify(new RSASSAVerifier(key.toRSAPublicKey())) || !issuer.equals(c.getIssuer())
                    || !c.getAudience().contains(audience) || c.getExpirationTime() == null
                    || !c.getExpirationTime().toInstant().isAfter(Instant.now()))
                throw new IllegalArgumentException();
            Set<String> permissions = Set.copyOf(c.getStringListClaim("permissions"));
            Set<UUID> scopes = c.getStringListClaim("branch_scopes").stream().map(UUID::fromString)
                    .collect(Collectors.toSet());
            return new Actor(UUID.fromString(c.getSubject()), permissions, scopes,
                    Boolean.TRUE.equals(c.getBooleanClaim("global_scope")));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid access token");
        }
    }

    private RSAKey key(String kid, boolean force) throws Exception {
        CachedKeys current = cached;
        if (force || Instant.now().isAfter(current.expiresAt)) {
            synchronized (this) {
                current = cached;
                if (force || Instant.now().isAfter(current.expiresAt)) {
                    HttpRequest request = HttpRequest.newBuilder(jwksUri).timeout(Duration.ofSeconds(3)).GET().build();
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200)
                        throw new IllegalStateException("JWKS endpoint returned " + response.statusCode());
                    Map<String, RSAKey> keys = new HashMap<>();
                    for (JWK jwk : JWKSet.parse(response.body()).getKeys())
                        if (jwk instanceof RSAKey rsa && !rsa.isPrivate())
                            keys.put(rsa.getKeyID(), rsa);
                    cached = current = new CachedKeys(Map.copyOf(keys), Instant.now().plus(cacheTtl));
                }
            }
        }
        return current.keys.get(kid);
    }

    private record CachedKeys(Map<String, RSAKey> keys, Instant expiresAt) {
    }
}

