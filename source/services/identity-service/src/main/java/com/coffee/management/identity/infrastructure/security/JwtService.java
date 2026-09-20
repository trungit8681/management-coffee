package com.coffee.management.identity.infrastructure.security;

import com.coffee.management.identity.application.IdentityException;
import com.coffee.management.identity.domain.model.EffectiveAuthorization;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyFactory;
import java.security.interfaces.*;
import java.security.spec.*;
import java.text.ParseException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
    private final String issuer, audience, keyId;
    private final Duration accessTtl;
    private final RSAKey key;

    @Autowired
    public JwtService(@Value("${identity.jwt.issuer}") String issuer,
            @Value("${identity.jwt.audience}") String audience,
            @Value("${identity.jwt.key-id}") String keyId, @Value("${identity.jwt.access-ttl}") Duration accessTtl,
            @Value("${identity.jwt.private-key-path:}") String privatePath,
            @Value("${identity.jwt.public-key-path:}") String publicPath,
            @Value("${identity.jwt.generate-if-missing:false}") boolean generateIfMissing) throws JOSEException {
        this.issuer = issuer;
        this.audience = audience;
        this.keyId = keyId;
        this.accessTtl = accessTtl;
        this.key = loadOrCreateKey(keyId, privatePath, publicPath, generateIfMissing);
    }

    public JwtService(String issuer, String audience, String keyId, Duration accessTtl) throws JOSEException {
        this(issuer, audience, keyId, accessTtl, "", "", true);
    }

    public IssuedAccessToken issue(UUID userId, long securityVersion, EffectiveAuthorization authorization,
            Instant now) {
        try {
            Instant expires = now.plus(accessTtl);
            JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(userId.toString()).issuer(issuer)
                    .audience(audience).jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now))
                    .expirationTime(Date.from(expires))
                    .claim("security_version", securityVersion)
                    .claim("permissions", authorization.permissions().stream().sorted().toList())
                    .claim("branch_scopes", authorization.branchScopes().stream().map(UUID::toString).sorted().toList())
                    .claim("global_scope", authorization.globalScope()).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return new IssuedAccessToken(jwt.serialize(), expires);
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to sign access token", e);
        }
    }

    public AccessPrincipal verify(String token, Instant now) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) || !keyId.equals(jwt.getHeader().getKeyID())
                    || !jwt.verify(new RSASSAVerifier(key.toRSAPublicKey())) || !issuer.equals(c.getIssuer())
                    || !c.getAudience().contains(audience) || c.getExpirationTime() == null
                    || !c.getExpirationTime().toInstant().isAfter(now))
                throw IdentityException.unauthorized();
            return new AccessPrincipal(UUID.fromString(c.getSubject()), c.getLongClaim("security_version"),
                    Set.copyOf(c.getStringListClaim("permissions")),
                    c.getStringListClaim("branch_scopes").stream().map(UUID::fromString)
                            .collect(java.util.stream.Collectors.toSet()),
                    Boolean.TRUE.equals(c.getBooleanClaim("global_scope")));
        } catch (ParseException | JOSEException | RuntimeException e) {
            if (e instanceof IdentityException i)
                throw i;
            throw IdentityException.unauthorized();
        }
    }

    public Map<String, Object> publicJwkSet() {
        return Map.of("keys", List.of(key.toPublicJWK().toJSONObject()));
    }

    private static RSAKey loadOrCreateKey(String keyId, String privatePath, String publicPath, boolean generate)
            throws JOSEException {
        if (privatePath == null || privatePath.isBlank() || publicPath == null || publicPath.isBlank()) {
            if (!generate)
                throw new IllegalStateException("Identity signing key paths are required");
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        }
        Path privateFile = Path.of(privatePath), publicFile = Path.of(publicPath),
                lockFile = privateFile.resolveSibling("jwt-key.lock");
        try {
            Files.createDirectories(privateFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                if (!Files.exists(privateFile) || !Files.exists(publicFile)) {
                    if (!generate)
                        throw new IllegalStateException("Configured identity signing key files do not exist");
                    RSAKey generated = new RSAKeyGenerator(2048).keyID(keyId).generate();
                    writePem(privateFile, "PRIVATE KEY", generated.toRSAPrivateKey().getEncoded(), true);
                    writePem(publicFile, "PUBLIC KEY", generated.toRSAPublicKey().getEncoded(), false);
                }
                RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(readPem(privateFile, "PRIVATE KEY")));
                RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(readPem(publicFile, "PUBLIC KEY")));
                return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load or create identity signing key", e);
        }
    }

    private static byte[] readPem(Path path, String type) throws Exception {
        return Base64.getMimeDecoder().decode(Files.readString(path).replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "").replaceAll("\\s", ""));
    }

    private static void writePem(Path path, String type, byte[] encoded, boolean privateFile) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(path,
                    privateFile ? java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                            : java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    public record IssuedAccessToken(String value, Instant expiresAt) {
    }

    public record AccessPrincipal(UUID userId, long securityVersion, Set<String> permissions, Set<UUID> branchScopes,
            boolean globalScope) {
    }
}
