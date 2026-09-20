package com.coffee.management.identity.infrastructure.bootstrap;

import com.coffee.management.identity.application.port.IdentityRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final IdentityRepository repository;
    private final PasswordEncoder passwords;
    private final String username, password, displayName;

    public AdminBootstrap(IdentityRepository repository, PasswordEncoder passwords,
            @Value("${identity.bootstrap.username:}") String username,
            @Value("${identity.bootstrap.password:}") String password,
            @Value("${identity.bootstrap.display-name:System Administrator}") String displayName) {
        this.repository = repository;
        this.passwords = passwords;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank() || repository.bootstrapAdminExists(username))
            return;
        if (password.length() < 12)
            throw new IllegalStateException("Bootstrap admin password must contain at least 12 characters");
        repository.bootstrapAdmin(username, passwords.encode(password), displayName, Instant.now());
    }
}
