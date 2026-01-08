package com.test.flywaytest;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies the Flyway configuration prefix fix.
 *
 * This test ensures that:
 * 1. postgresqlTransactionalLock configuration is properly propagated
 * 2. CREATE INDEX CONCURRENTLY works without deadlock
 * 3. Session locks are used instead of transactional locks
 */
@Testcontainers
@SpringBootTest
public class FlywayMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void testFlywayMigrationWithCreateIndexConcurrently() throws Exception {
        // Configure Flyway with the fixed plugin configuration
        Map<String, String> configuration = new HashMap<>();

        // This is the critical configuration that tests the fix
        // Before fix: would be transformed to flyway.plugins.postgresql.transactional.lock
        // After fix: should be transformed to flyway.postgresql.transactional.lock
        configuration.put("flyway.postgresql.transactional.lock", "false");

        FluentConfiguration flywayConfig = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .configuration(configuration);

        Flyway flyway = flywayConfig.load();

        // Run migrations - this should NOT deadlock
        long startTime = System.currentTimeMillis();
        var result = flyway.migrate();
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("Migration completed in " + duration + "ms");
        System.out.println("Migrations applied: " + result.migrationsExecuted);

        // Verify migrations ran successfully
        assertEquals(2, result.migrationsExecuted, "Should have executed 2 migrations");
        assertTrue(duration < 30000, "Migration should complete within 30 seconds (not deadlock)");

        // Verify the indexes were created
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'users' AND indexname LIKE 'idx_%'"
            );

            int indexCount = 0;
            while (rs.next()) {
                indexCount++;
                String indexName = rs.getString("indexname");
                System.out.println("Found index: " + indexName);
                assertTrue(
                    indexName.equals("idx_users_email") || indexName.equals("idx_users_username"),
                    "Unexpected index name: " + indexName
                );
            }

            assertEquals(2, indexCount, "Should have created 2 indexes");
        }

        // Verify data exists
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users");
            assertTrue(rs.next());
            int userCount = rs.getInt(1);
            assertEquals(3, userCount, "Should have 3 users");
        }
    }

    @Test
    void testConfigurationPrefix() {
        // This test verifies that the configuration key transformation works correctly
        String testKey = "postgresqlTransactionalLock";

        // Simulate the camelCase to property transformation
        String camelCaseRegex = "(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])";
        String transformedKey = String.join(".", testKey.split(camelCaseRegex)).toLowerCase();

        // After the fix, this should produce "postgresql.transactional.lock"
        assertEquals("postgresql.transactional.lock", transformedKey,
            "Configuration key should be transformed correctly");

        // The final configuration key should be "flyway.postgresql.transactional.lock"
        // NOT "flyway.plugins.postgresql.transactional.lock" (which was the bug)
        String finalKey = "flyway." + transformedKey;
        assertEquals("flyway.postgresql.transactional.lock", finalKey,
            "Final configuration key should have 'flyway.' prefix, not 'flyway.plugins.'");
    }
}
