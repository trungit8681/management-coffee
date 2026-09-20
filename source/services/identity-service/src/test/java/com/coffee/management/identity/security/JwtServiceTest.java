package com.coffee.management.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffee.management.identity.domain.model.EffectiveAuthorization;
import com.coffee.management.identity.infrastructure.security.JwtService;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class JwtServiceTest {
    @TempDir Path keys;
    @Test void issuesAndValidatesRs256Claims() throws Exception {
        JwtService service=new JwtService("issuer","audience","kid-1",Duration.ofMinutes(15));
        UUID user=UUID.randomUUID(),branch=UUID.randomUUID(); Instant now=Instant.parse("2026-01-01T00:00:00Z");
        var token=service.issue(user,3,new EffectiveAuthorization(Set.of("order:create"),Set.of(branch),false),now);
        var principal=service.verify(token.value(),now.plusSeconds(1));
        assertThat(principal.userId()).isEqualTo(user);
        assertThat(principal.securityVersion()).isEqualTo(3);
        assertThat(principal.permissions()).containsExactly("order:create");
        assertThat(principal.branchScopes()).containsExactly(branch);
    }

    @Test void rejectsExpiredToken() throws Exception {
        JwtService service=new JwtService("issuer","audience","kid-1",Duration.ofSeconds(1));
        Instant now=Instant.parse("2026-01-01T00:00:00Z");
        String token=service.issue(UUID.randomUUID(),1,new EffectiveAuthorization(Set.of(),Set.of(),false),now).value();
        assertThatThrownBy(()->service.verify(token,now.plusSeconds(2))).isInstanceOf(RuntimeException.class);
    }

    @Test void persistsSigningKeyAcrossRestartAndPublishesPublicOnlyJwks() throws Exception {
        Path privateKey=keys.resolve("private.pem"),publicKey=keys.resolve("public.pem");
        JwtService first=new JwtService("issuer","audience","kid-stable",Duration.ofMinutes(15),privateKey.toString(),publicKey.toString(),true);
        Instant now=Instant.now();String token=first.issue(UUID.randomUUID(),1,new EffectiveAuthorization(Set.of(),Set.of(),true),now).value();
        JwtService restarted=new JwtService("issuer","audience","kid-stable",Duration.ofMinutes(15),privateKey.toString(),publicKey.toString(),false);
        assertThat(restarted.verify(token,now.plusSeconds(1))).isNotNull();
        var published=(java.util.Map<?,?>)((java.util.List<?>)restarted.publicJwkSet().get("keys")).getFirst();
        assertThat(published.get("kid")).isEqualTo("kid-stable");
        assertThat(Set.of("d","p","q","dp","dq","qi")).noneMatch(published::containsKey);
    }
}
