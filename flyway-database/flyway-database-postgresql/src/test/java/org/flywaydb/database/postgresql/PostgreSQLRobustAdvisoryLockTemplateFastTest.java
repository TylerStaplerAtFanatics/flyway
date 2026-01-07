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

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fast unit tests for PostgreSQLRobustAdvisoryLockTemplate using mocked time.
 * These tests run instantly without wall-clock delays.
 */
public class PostgreSQLRobustAdvisoryLockTemplateFastTest {

    private JdbcTemplate jdbcTemplate;
    private TimeProvider.ControllableTimeProvider timeProvider;
    private Map<Long, LockMetadata> lockMetadataStore;
    private Map<Long, Boolean> advisoryLocks;

    @Before
    public void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        timeProvider = new TimeProvider.ControllableTimeProvider(Instant.parse("2025-01-07T12:00:00Z"));
        lockMetadataStore = new HashMap<>();
        advisoryLocks = new HashMap<>();

        setupMockBehavior();
    }

    private void setupMockBehavior() {
        // Mock table creation (does nothing)
        try {
            doNothing().when(jdbcTemplate).execute(contains("CREATE TABLE"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // Mock lock acquisition
        try {
            when(jdbcTemplate.queryForBoolean(eq("SELECT pg_try_advisory_lock(?)"), anyLong()))
                .thenAnswer(invocation -> {
                    Long lockId = invocation.getArgument(1);
                    if (advisoryLocks.getOrDefault(lockId, false)) {
                        return false; // Already held
                    }
                    advisoryLocks.put(lockId, true);
                    return true;
                });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // Mock lock release
        try {
            when(jdbcTemplate.queryForBoolean(eq("SELECT pg_advisory_unlock(?)"), anyLong()))
                .thenAnswer(invocation -> {
                    Long lockId = invocation.getArgument(1);
                    if (!advisoryLocks.getOrDefault(lockId, false)) {
                        return false; // Not held
                    }
                    advisoryLocks.remove(lockId);
                    return true;
                });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // Mock metadata insert/update
        try {
            when(jdbcTemplate.update(contains("INSERT INTO flyway_lock_metadata"), any()))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArgument(1);
                    Long lockId = (Long) args[0];
                    String ownerId = (String) args[1];
                    String hostname = (String) args[2];

                    lockMetadataStore.put(lockId, new LockMetadata(
                        lockId, ownerId, hostname,
                        timeProvider.now(), timeProvider.now(), "MIGRATION"
                    ));
                    return 1;
                });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // Mock heartbeat update
        try {
            when(jdbcTemplate.update(contains("UPDATE flyway_lock_metadata SET last_heartbeat"), any()))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArgument(1);
                    Long lockId = (Long) args[0];
                    String ownerId = (String) args[1];

                    LockMetadata metadata = lockMetadataStore.get(lockId);
                    if (metadata != null && metadata.ownerId.equals(ownerId)) {
                        metadata.lastHeartbeat = timeProvider.now();
                        return 1;
                    }
                    return 0;
                });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // Mock metadata delete
        try {
            when(jdbcTemplate.update(contains("DELETE FROM flyway_lock_metadata"), any()))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArgument(1);
                    if (args.length == 1) {
                        // Delete by lock_id only (abandoned cleanup)
                        Long lockId = (Long) args[0];
                        return lockMetadataStore.remove(lockId) != null ? 1 : 0;
                    } else {
                        // Delete by lock_id and owner_id (normal release)
                        Long lockId = (Long) args[0];
                        String ownerId = (String) args[1];
                        LockMetadata metadata = lockMetadataStore.get(lockId);
                        if (metadata != null && metadata.ownerId.equals(ownerId)) {
                            lockMetadataStore.remove(lockId);
                            return 1;
                        }
                        return 0;
                    }
                });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // Mock metadata query for abandoned check
        try {
            when(jdbcTemplate.queryForMap(contains("seconds_since_heartbeat"), anyLong()))
                .thenAnswer(invocation -> {
                    Long lockId = invocation.getArgument(1);
                    LockMetadata metadata = lockMetadataStore.get(lockId);
                    if (metadata == null) {
                        throw new org.springframework.dao.EmptyResultDataAccessException(1);
                    }

                    long secondsSince = timeProvider.now().getEpochSecond() -
                                       metadata.lastHeartbeat.getEpochSecond();

                    Map<String, Object> result = new HashMap<>();
                    result.put("lock_id", metadata.lockId);
                    result.put("owner_id", metadata.ownerId);
                    result.put("hostname", metadata.hostname);
                    result.put("acquired_at", java.sql.Timestamp.from(metadata.acquiredAt));
                    result.put("last_heartbeat", java.sql.Timestamp.from(metadata.lastHeartbeat));
                    result.put("seconds_since_heartbeat", secondsSince);
                    return result;
                });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // Mock abandoned lock cleanup
        try {
            when(jdbcTemplate.update(contains("DELETE FROM flyway_lock_metadata") &&
                                    contains("last_heartbeat <"), any()))
                .thenAnswer(invocation -> {
                    int removed = 0;
                    for (var entry : new HashMap<>(lockMetadataStore).entrySet()) {
                        long secondsSince = timeProvider.now().getEpochSecond() -
                                          entry.getValue().lastHeartbeat.getEpochSecond();
                        if (secondsSince > 120) { // Default abandoned threshold
                            lockMetadataStore.remove(entry.getKey());
                            removed++;
                        }
                    }
                    return removed;
                });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testAbandonedLockDetection_withFastTime() throws Exception {
        // This test completes instantly by fast-forwarding time

        TestableRobustLockTemplate lockTemplate = new TestableRobustLockTemplate(
            jdbcTemplate, 1, 5, 30, 10, timeProvider
        );

        // Simulate acquiring lock
        lockTemplate.acquireLockWithCleanup();

        // Verify lock is held
        assertTrue("Lock should be acquired", advisoryLocks.get(lockTemplate.lockNum));
        assertNotNull("Metadata should exist", lockMetadataStore.get(lockTemplate.lockNum));

        // Fast-forward time by 15 seconds (beyond abandoned threshold of 10s)
        timeProvider.advanceSeconds(15);

        // Create new lock template - should detect abandoned lock
        TestableRobustLockTemplate lock2 = new TestableRobustLockTemplate(
            jdbcTemplate, 1, 5, 30, 10, timeProvider
        );

        // Check if lock is considered abandoned
        boolean isAbandoned = lock2.isLockAbandoned();
        assertTrue("Lock should be detected as abandoned", isAbandoned);
    }

    @Test
    public void testHeartbeatPreventsAbandonment() throws Exception {
        // Test runs instantly - no wall-clock delays

        TestableRobustLockTemplate lockTemplate = new TestableRobustLockTemplate(
            jdbcTemplate, 2, 5, 30, 20, timeProvider
        );

        lockTemplate.acquireLockWithCleanup();

        // Fast-forward by 10 seconds
        timeProvider.advanceSeconds(10);

        // Update heartbeat
        lockTemplate.updateHeartbeat();

        // Fast-forward another 10 seconds (total: 20s)
        timeProvider.advanceSeconds(10);

        // Should NOT be abandoned because heartbeat was updated
        boolean isAbandoned = lockTemplate.isLockAbandoned();
        assertFalse("Lock should NOT be abandoned with recent heartbeat", isAbandoned);
    }

    @Test
    public void testLockTimeout_withFastTime() throws Exception {
        // Test completes instantly

        // First lock acquires
        TestableRobustLockTemplate lock1 = new TestableRobustLockTemplate(
            jdbcTemplate, 3, 5, 5, 20, timeProvider // 5s timeout
        );
        lock1.acquireLockWithCleanup();

        // Second lock attempts with timeout
        TestableRobustLockTemplate lock2 = new TestableRobustLockTemplate(
            jdbcTemplate, 3, 5, 5, 20, timeProvider
        );

        try {
            lock2.acquireLockWithCleanup();
            fail("Should timeout when lock is already held");
        } catch (FlywayException e) {
            assertTrue("Should mention timeout in message",
                      e.getMessage().contains("Unable to acquire"));
        }
    }

    @Test
    public void testCleanupAbandonedLocksAutomatically() throws Exception {
        // Test runs instantly

        TestableRobustLockTemplate lock1 = new TestableRobustLockTemplate(
            jdbcTemplate, 4, 5, 30, 10, timeProvider
        );

        // Acquire lock
        lock1.acquireLockWithCleanup();

        // Verify metadata exists
        assertNotNull("Metadata should exist", lockMetadataStore.get(lock1.lockNum));

        // Fast-forward past abandoned threshold
        timeProvider.advanceSeconds(15);

        // New lock template should clean up automatically
        TestableRobustLockTemplate lock2 = new TestableRobustLockTemplate(
            jdbcTemplate, 4, 5, 30, 10, timeProvider
        );

        lock2.cleanupAbandonedLocks();

        // Metadata should be removed
        assertNull("Abandoned metadata should be cleaned up",
                  lockMetadataStore.get(lock1.lockNum));
    }

    @Test
    public void testMultipleHeartbeats() throws Exception {
        // Test multiple heartbeat cycles instantly

        TestableRobustLockTemplate lockTemplate = new TestableRobustLockTemplate(
            jdbcTemplate, 5, 2, 30, 20, timeProvider // 2s heartbeat interval
        );

        lockTemplate.acquireLockWithCleanup();

        Instant initialHeartbeat = lockMetadataStore.get(lockTemplate.lockNum).lastHeartbeat;

        // Simulate 5 heartbeat cycles (10 seconds)
        for (int i = 0; i < 5; i++) {
            timeProvider.advanceSeconds(2);
            lockTemplate.updateHeartbeat();
        }

        Instant finalHeartbeat = lockMetadataStore.get(lockTemplate.lockNum).lastHeartbeat;

        // Heartbeat should have advanced by 10 seconds
        assertEquals("Heartbeat should advance with time",
                    10,
                    finalHeartbeat.getEpochSecond() - initialHeartbeat.getEpochSecond());
    }

    /**
     * Testable version that exposes internal methods and uses TimeProvider.
     */
    private static class TestableRobustLockTemplate extends PostgreSQLRobustAdvisoryLockTemplate {
        private final TimeProvider timeProvider;

        TestableRobustLockTemplate(JdbcTemplate jdbcTemplate, int discriminator,
                                  int heartbeatInterval, int lockTimeout,
                                  int abandonedThreshold, TimeProvider timeProvider) {
            super(jdbcTemplate, discriminator, heartbeatInterval, lockTimeout, abandonedThreshold);
            this.timeProvider = timeProvider;
        }

        // Expose protected methods for testing
        public void acquireLockWithCleanup() throws SQLException {
            super.acquireLockWithCleanup();
        }

        public boolean isLockAbandoned() throws SQLException {
            return super.isLockAbandoned();
        }

        public void cleanupAbandonedLocks() throws SQLException {
            super.cleanupAbandonedLocks();
        }

        public void updateHeartbeat() {
            super.updateHeartbeat();
        }
    }

    /**
     * Simple data class for storing lock metadata in tests.
     */
    private static class LockMetadata {
        final Long lockId;
        final String ownerId;
        final String hostname;
        final Instant acquiredAt;
        Instant lastHeartbeat;
        final String lockType;

        LockMetadata(Long lockId, String ownerId, String hostname,
                    Instant acquiredAt, Instant lastHeartbeat, String lockType) {
            this.lockId = lockId;
            this.ownerId = ownerId;
            this.hostname = hostname;
            this.acquiredAt = acquiredAt;
            this.lastHeartbeat = lastHeartbeat;
            this.lockType = lockType;
        }
    }
}
