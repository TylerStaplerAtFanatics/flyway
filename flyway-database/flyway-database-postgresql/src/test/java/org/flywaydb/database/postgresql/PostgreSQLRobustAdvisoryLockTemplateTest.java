/*-
 * ========================LICENSE_START=================================
 * flyway-database-postgresql
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
package org.flywaydb.database.postgresql;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.internal.jdbc.JdbcTemplate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.*;

import static org.junit.Assert.*;

/**
 * Integration tests for PostgreSQLRobustAdvisoryLockTemplate.
 * Tests abandoned lock cleanup, heartbeat mechanism, and timeout handling.
 */
public class PostgreSQLRobustAdvisoryLockTemplateTest {

    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate jdbcTemplate;
    private Connection connection;

    @Before
    public void setUp() throws Exception {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");
        postgres.start();

        connection = DriverManager.getConnection(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        );
        jdbcTemplate = new JdbcTemplate(connection);
    }

    @After
    public void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    public void testBasicLockAcquisitionAndRelease() throws Exception {
        PostgreSQLRobustAdvisoryLockTemplate lockTemplate =
            new PostgreSQLRobustAdvisoryLockTemplate(jdbcTemplate, 1, 5, 30, 60);

        // Execute with lock
        String result = lockTemplate.execute(() -> {
            // Verify lock is held
            var status = lockTemplate.getLockStatus();
            assertTrue("Lock should be held", status.held);
            assertNotNull("Owner ID should be set", status.ownerId);
            return "success";
        });

        assertEquals("success", result);

        // Verify lock is released
        var status = lockTemplate.getLockStatus();
        assertFalse("Lock should be released", status.held);
    }

    @Test
    public void testHeartbeatUpdates() throws Exception {
        PostgreSQLRobustAdvisoryLockTemplate lockTemplate =
            new PostgreSQLRobustAdvisoryLockTemplate(jdbcTemplate, 2, 2, 30, 60);

        lockTemplate.execute(() -> {
            // Get initial heartbeat
            var status1 = lockTemplate.getLockStatus();
            long heartbeat1 = status1.secondsSinceHeartbeat;

            // Wait for heartbeat update (2s interval + 1s buffer)
            Thread.sleep(3000);

            // Get updated heartbeat
            var status2 = lockTemplate.getLockStatus();
            long heartbeat2 = status2.secondsSinceHeartbeat;

            // Heartbeat should have been updated (seconds since should be small)
            assertTrue("Heartbeat should be recent", heartbeat2 < 5);

            return null;
        });
    }

    @Test(timeout = 10000)
    public void testSerialLockAcquisition() throws Exception {
        PostgreSQLRobustAdvisoryLockTemplate lock1 =
            new PostgreSQLRobustAdvisoryLockTemplate(jdbcTemplate, 3, 5, 30, 60);
        PostgreSQLRobustAdvisoryLockTemplate lock2 =
            new PostgreSQLRobustAdvisoryLockTemplate(jdbcTemplate, 3, 5, 5, 60);

        // First lock acquires
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<String> future1 = executor.submit(() ->
            lock1.execute(() -> {
                Thread.sleep(2000); // Hold for 2 seconds
                return "first";
            })
        );

        // Give first lock time to acquire
        Thread.sleep(500);

        // Second lock should wait and timeout
        Future<String> future2 = executor.submit(() -> {
            try {
                return lock2.execute(() -> "second");
            } catch (FlywayException e) {
                return "timeout";
            }
        });

        assertEquals("first", future1.get());
        assertEquals("timeout", future2.get()); // Should timeout waiting for lock

        executor.shutdownNow();
    }

    @Test(timeout = 20000)
    public void testAbandonedLockCleanup() throws Exception {
        // Create lock with short abandoned threshold for testing
        PostgreSQLRobustAdvisoryLockTemplate lock1 =
            new PostgreSQLRobustAdvisoryLockTemplate(jdbcTemplate, 4, 1, 30, 5); // 5s abandoned

        // Simulate abandoned lock by acquiring but not releasing properly
        CountDownLatch lockAcquired = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> abandonedTask = executor.submit(() -> {
            try {
                lock1.execute(() -> {
                    lockAcquired.countDown();
                    // Simulate process crash - sleep forever
                    Thread.sleep(Long.MAX_VALUE);
                    return null;
                });
            } catch (InterruptedException e) {
                // Simulated crash
            }
            return null;
        });

        // Wait for lock to be acquired
        lockAcquired.await();

        // Verify lock is held
        var status = lock1.getLockStatus();
        assertTrue("Lock should be held", status.held);

        // Simulate process crash by interrupting thread
        executor.shutdownNow();
        abandonedTask.cancel(true);

        // Wait for lock to be considered abandoned (5s + buffer)
        Thread.sleep(6000);

        // Create new lock template that should detect and clean up abandoned lock
        PostgreSQLRobustAdvisoryLockTemplate lock2 =
            new PostgreSQLRobustAdvisoryLockTemplate(jdbcTemplate, 4, 1, 10, 5);

        // Should successfully acquire after cleaning up abandoned lock
        String result = lock2.execute(() -> "recovered");
        assertEquals("recovered", result);
    }

    @Test
    public void testParallelLockAttempts() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch startLatch = new CountDownLatch(1);
        int numThreads = 5;

        // All threads try to acquire same lock simultaneously
        Future<Boolean>[] futures = new Future[numThreads];
        for (int i = 0; i < numThreads; i++) {
            futures[i] = executor.submit(() -> {
                startLatch.await(); // Wait for all threads to be ready

                PostgreSQLRobustAdvisoryLockTemplate lock =
                    new PostgreSQLRobustAdvisoryLockTemplate(jdbcTemplate, 5, 5, 5, 60);

                try {
                    lock.execute(() -> {
                        Thread.sleep(100); // Hold lock briefly
                        return null;
                    });
                    return true; // Successfully acquired and released
                } catch (FlywayException e) {
                    return false; // Timeout
                }
            });
        }

        // Start all threads
        startLatch.countDown();

        // Wait for all to complete
        int successes = 0;
        int timeouts = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(15, TimeUnit.SECONDS)) {
                successes++;
            } else {
                timeouts++;
            }
        }

        // At least one should succeed, others may timeout
        assertTrue("At least one thread should acquire lock", successes >= 1);
        System.out.println("Successes: " + successes + ", Timeouts: " + timeouts);

        executor.shutdownNow();
    }

    @Test
    public void testLockMetadataTable() throws Exception {
        PostgreSQLRobustAdvisoryLockTemplate lockTemplate =
            new PostgreSQLRobustAdvisoryLockTemplate(jdbcTemplate, 6, 5, 30, 60);

        // Verify metadata table exists
        boolean tableExists = jdbcTemplate.queryForBoolean(
            "SELECT EXISTS (" +
            "  SELECT FROM information_schema.tables " +
            "  WHERE table_name = 'flyway_lock_metadata'" +
            ")"
        );

        assertTrue("Lock metadata table should exist", tableExists);

        // Execute with lock and check metadata
        lockTemplate.execute(() -> {
            var metadata = jdbcTemplate.queryForMap(
                "SELECT * FROM flyway_lock_metadata WHERE lock_id = ?",
                lockTemplate.lockNum
            );

            assertNotNull("Metadata should exist while lock is held", metadata);
            assertNotNull("Owner ID should be set", metadata.get("owner_id"));
            assertNotNull("Hostname should be set", metadata.get("hostname"));
            assertNotNull("Acquired timestamp should be set", metadata.get("acquired_at"));
            assertNotNull("Heartbeat timestamp should be set", metadata.get("last_heartbeat"));
            assertEquals("Lock type should be MIGRATION", "MIGRATION", metadata.get("lock_type"));

            return null;
        });

        // Verify metadata is cleaned up after release
        try {
            jdbcTemplate.queryForMap(
                "SELECT * FROM flyway_lock_metadata WHERE lock_id = ?",
                lockTemplate.lockNum
            );
            fail("Metadata should be deleted after lock release");
        } catch (Exception e) {
            // Expected - no rows found
        }
    }

    @Test
    public void testLockStatusMonitoring() throws Exception {
        PostgreSQLRobustAdvisoryLockTemplate lockTemplate =
            new PostgreSQLRobustAdvisoryLockTemplate(jdbcTemplate, 7, 5, 30, 60);

        // Initially not held
        var statusBefore = lockTemplate.getLockStatus();
        assertFalse("Lock should not be held initially", statusBefore.held);

        // Execute with lock
        lockTemplate.execute(() -> {
            var statusDuring = lockTemplate.getLockStatus();
            assertTrue("Lock should be held during execution", statusDuring.held);
            assertNotNull("Owner ID should be set", statusDuring.ownerId);
            assertNotNull("Hostname should be set", statusDuring.hostname);
            assertNotNull("Acquired time should be set", statusDuring.acquiredAt);
            assertNotNull("Last heartbeat should be set", statusDuring.lastHeartbeat);
            assertTrue("Heartbeat should be recent",
                      statusDuring.secondsSinceHeartbeat < 10);

            return null;
        });

        // After release
        var statusAfter = lockTemplate.getLockStatus();
        assertFalse("Lock should not be held after release", statusAfter.held);
    }

    @Test(expected = FlywayException.class)
    public void testLockTimeoutException() throws Exception {
        // Create two lock templates with short timeout
        PostgreSQLRobustAdvisoryLockTemplate lock1 =
            new PostgreSQLRobustAdvisoryLockTemplate(jdbcTemplate, 8, 5, 30, 60);
        PostgreSQLRobustAdvisoryLockTemplate lock2 =
            new PostgreSQLRobustAdvisoryLockTemplate(jdbcTemplate, 8, 5, 2, 60); // 2s timeout

        ExecutorService executor = Executors.newSingleThreadExecutor();

        // First lock holds for 5 seconds
        Future<?> holder = executor.submit(() ->
            lock1.execute(() -> {
                Thread.sleep(5000);
                return null;
            })
        );

        // Give first lock time to acquire
        Thread.sleep(500);

        try {
            // Second lock should timeout after 2 seconds
            lock2.execute(() -> null);
        } finally {
            holder.cancel(true);
            executor.shutdownNow();
        }
    }
}
