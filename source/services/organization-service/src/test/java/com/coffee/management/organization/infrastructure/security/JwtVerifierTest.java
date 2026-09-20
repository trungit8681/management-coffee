package com.coffee.management.organization.infrastructure.security;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JwtVerifierTest {
    @Test
    void fetchesJwksVerifiesClaimsAndUsesCacheWhenIdentityIsUnavailable() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("kid-1").generate();
        String body = new com.nimbusds.jose.jwk.JWKSet(key.toPublicJWK()).toString();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", e -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
            JwtVerifier verifier = new JwtVerifier("issuer", "audience", uri, Duration.ofMinutes(5),
                    HttpClient.newHttpClient());
            String token = token(key);
            assertThat(verifier.verify(token).permissions()).contains("organization:view_branch");
            server.stop(0);
            assertThat(verifier.verify(token).globalScope()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnknownKid() throws Exception {
        var published = new RSAKeyGenerator(2048).keyID("kid-1").generate();
        var other = new RSAKeyGenerator(2048).keyID("kid-2").generate();
        String body = new com.nimbusds.jose.jwk.JWKSet(published.toPublicJWK()).toString();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", e -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
        });
        server.start();
        try {
            JwtVerifier verifier = new JwtVerifier("issuer", "audience",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks"), Duration.ofMinutes(5),
                    HttpClient.newHttpClient());
            assertThatThrownBy(() -> verifier.verify(token(other))).isInstanceOf(IllegalArgumentException.class);
        } finally {
            server.stop(0);
        }
    }

    private static String token(com.nimbusds.jose.jwk.RSAKey key) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet c = new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer("issuer")
                .audience("audience").expirationTime(Date.from(now.plusSeconds(60))).claim("security_version", 1)
                .claim("permissions", List.of("organization:view_branch")).claim("branch_scopes", List.of())
                .claim("global_scope", true).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), c);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
