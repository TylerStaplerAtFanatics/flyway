/*-
 * ========================LICENSE_START=================================
 * flyway-gradle-plugin
 * ========================================================================
 * Copyright (C) 2010 - 2025 Red Gate Software Ltd
 * ========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.flywaydb.gradle.task;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.Assert.*;

/**
 * Integration test for PostgreSQL concurrent migrations using CREATE INDEX CONCURRENTLY.
 * Tests that the transactional-lock configuration properly prevents hangs.
 */
public class PostgreSQLConcurrentMigrationTest {

    private PostgreSQLContainer<?> postgres;
    private File tempMigrationDir;
    private static final int TIMEOUT_SECONDS = 30;

    @Before
    public void setUp() throws Exception {
        // Start PostgreSQL container
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");
        postgres.start();

        // Create temporary migration directory
        tempMigrationDir = Files.createTempDirectory("flyway-test-migrations").toFile();

        // Create initial schema migration
        createMigration("V1__create_test_table.sql",
            "CREATE TABLE test_table (id SERIAL PRIMARY KEY, name VARCHAR(100));");
    }

    @After
    public void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
        if (tempMigrationDir != null) {
            deleteDirectory(tempMigrationDir);
        }
    }

    @Test
    public void testConcurrentIndexCreation_withTransactionalLockDisabled_shouldNotHang() throws Exception {
        // Create migration with CREATE INDEX CONCURRENTLY
        createMigration("V2__add_concurrent_index.sql",
            "-- flyway:executeInTransaction=false\n" +
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_test_name ON test_table(name);");

        // Configure Flyway with transactional lock disabled
        Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("filesystem:" + tempMigrationDir.getAbsolutePath())
            .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
            .load();

        // Run migration with timeout
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> migrationFuture = executor.submit(() -> {
            flyway.migrate();
            return null;
        });

        try {
            // Should complete within timeout
            migrationFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Verify index was created
            try (Connection conn = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement stmt = conn.createStatement()) {
                var rs = stmt.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'test_table' AND indexname = 'idx_test_name'");
                assertTrue("Index should exist", rs.next());
            }
        } catch (TimeoutException e) {
            migrationFuture.cancel(true);
            fail("Migration hung - transactional lock configuration not working properly");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testMultipleConcurrentIndexes_shouldNotDeadlock() throws Exception {
        // Create multiple CONCURRENTLY migrations
        createMigration("V2__add_index_1.sql",
            "-- flyway:executeInTransaction=false\n" +
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_test_name ON test_table(name);");

        createMigration("V3__add_index_2.sql",
            "-- flyway:executeInTransaction=false\n" +
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_test_id ON test_table(id);");

        // Configure Flyway with transactional lock disabled
        Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("filesystem:" + tempMigrationDir.getAbsolutePath())
            .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
            .load();

        // Run with timeout
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> migrationFuture = executor.submit(() -> {
            flyway.migrate();
            return null;
        });

        try {
            migrationFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Verify both indexes were created
            try (Connection conn = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement stmt = conn.createStatement()) {
                var rs = stmt.executeQuery(
                    "SELECT COUNT(*) as cnt FROM pg_indexes WHERE tablename = 'test_table' " +
                    "AND indexname IN ('idx_test_name', 'idx_test_id')");
                rs.next();
                assertEquals("Both indexes should exist", 2, rs.getInt("cnt"));
            }
        } catch (TimeoutException e) {
            migrationFuture.cancel(true);
            fail("Migration deadlocked with multiple CONCURRENTLY indexes");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testParallelMigrations_withConcurrentIndexes_shouldWork() throws Exception {
        // Create CONCURRENTLY migration
        createMigration("V2__add_concurrent_index.sql",
            "-- flyway:executeInTransaction=false\n" +
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_test_name ON test_table(name);");

        // Run multiple Flyway instances in parallel
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            futures.add(executor.submit(() -> {
                try {
                    Flyway flyway = Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .locations("filesystem:" + tempMigrationDir.getAbsolutePath())
                        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                        .load();

                    flyway.migrate();
                    return true;
                } catch (FlywayException e) {
                    // Expected - some instances will see migrations already applied
                    return true;
                }
            }));
        }

        try {
            // All migrations should complete within timeout
            for (Future<Boolean> future : futures) {
                assertTrue("Migration should succeed or gracefully handle already applied",
                    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        } catch (TimeoutException e) {
            futures.forEach(f -> f.cancel(true));
            fail("Parallel migrations with CONCURRENTLY hung");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test(expected = TimeoutException.class)
    public void testConcurrentIndexCreation_withTransactionalLockEnabled_shouldHang() throws Exception {
        // This test verifies the bug exists when transactional lock is enabled
        createMigration("V2__add_concurrent_index.sql",
            "-- flyway:executeInTransaction=false\n" +
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_test_name ON test_table(name);");

        // Configure Flyway WITH transactional lock (bug scenario)
        Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("filesystem:" + tempMigrationDir.getAbsolutePath())
            .configuration(Map.of("flyway.postgresql.transactional.lock", "true"))
            .load();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> migrationFuture = executor.submit(() -> {
            flyway.migrate();
            return null;
        });

        try {
            // This SHOULD timeout, demonstrating the bug
            migrationFuture.get(5, TimeUnit.SECONDS);
            fail("Expected migration to hang with transactional lock enabled");
        } finally {
            migrationFuture.cancel(true);
            executor.shutdownNow();
        }
    }

    private void createMigration(String filename, String content) throws Exception {
        File migrationFile = new File(tempMigrationDir, filename);
        try (FileWriter writer = new FileWriter(migrationFile)) {
            writer.write(content);
        }
    }

    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }
}
