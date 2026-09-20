package com.coffee.management.identity.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class IdentityMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("identity")
                    .withUsername("identity_migration")
                    .withPassword("test-only-password");

    @Test
    void migrationCreatesIdentityOwnedTables() throws Exception {
        var migrationResult = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(migrationResult.migrationsExecuted).isEqualTo(3);

        Set<String> tables = new TreeSet<>();
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                     """)) {
            while (result.next()) {
                tables.add(result.getString("table_name"));
            }
        }

        assertThat(tables).contains(
                "identity_users",
                "roles",
                "permissions",
                "role_permissions",
                "user_role_assignments",
                "refresh_sessions",
                "refresh_tokens",
                "identity_audit_events",
                "outbox_events",
                "idempotency_records");
    }
}
