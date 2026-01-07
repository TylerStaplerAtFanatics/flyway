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

import lombok.CustomLog;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.internal.exception.FlywaySqlException;
import org.flywaydb.core.internal.jdbc.JdbcTemplate;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Robust advisory lock template for PostgreSQL that handles:
 * - Orphaned lock cleanup (process crashes)
 * - Lock timeouts (deadlock prevention)
 * - Heartbeat mechanism (liveness detection)
 * - Automatic recovery (abandoned session cleanup)
 *
 * This solves the fundamental problem with session-scoped advisory locks:
 * they don't auto-release on process crash like transaction-scoped locks do.
 */
@CustomLog
public class PostgreSQLRobustAdvisoryLockTemplate {

    private static final long LOCK_MAGIC_NUM =
            (0x46L << 40) // F
                    + (0x6CL << 32) // l
                    + (0x79L << 24) // y
                    + (0x77 << 16) // w
                    + (0x61 << 8) // a
                    + 0x79; // y

    private static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 10;
    private static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_ABANDONED_THRESHOLD_SECONDS = 120; // 2x timeout

    private final JdbcTemplate jdbcTemplate;
    private final long lockNum;
    private final String ownerId;
    private final String hostname;
    private final int heartbeatInterval;
    private final int lockTimeout;
    private final int abandonedThreshold;

    private ScheduledExecutorService heartbeatExecutor;
    private volatile boolean lockHeld = false;

    PostgreSQLRobustAdvisoryLockTemplate(JdbcTemplate jdbcTemplate,
                                         int discriminator,
                                         int heartbeatInterval,
                                         int lockTimeout,
                                         int abandonedThreshold) {
        this.jdbcTemplate = jdbcTemplate;
        this.lockNum = LOCK_MAGIC_NUM + discriminator;
        this.ownerId = generateOwnerId();
        this.hostname = getHostname();
        this.heartbeatInterval = heartbeatInterval > 0 ? heartbeatInterval : DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        this.lockTimeout = lockTimeout > 0 ? lockTimeout : DEFAULT_LOCK_TIMEOUT_SECONDS;
        this.abandonedThreshold = abandonedThreshold > 0 ? abandonedThreshold : DEFAULT_ABANDONED_THRESHOLD_SECONDS;

        ensureLockMetadataTable();
    }

    /**
     * Creates metadata table if it doesn't exist.
     * This table tracks lock ownership and heartbeat for abandoned lock detection.
     */
    private void ensureLockMetadataTable() {
        try {
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS flyway_lock_metadata (" +
                "    lock_id BIGINT PRIMARY KEY," +
                "    owner_id VARCHAR(255) NOT NULL," +
                "    hostname VARCHAR(255) NOT NULL," +
                "    acquired_at TIMESTAMP NOT NULL," +
                "    last_heartbeat TIMESTAMP NOT NULL," +
                "    lock_type VARCHAR(50) NOT NULL" +
                ")"
            );

            jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_flyway_lock_heartbeat " +
                "ON flyway_lock_metadata(last_heartbeat)"
            );
        } catch (SQLException e) {
            LOG.warn("Could not create lock metadata table (may already exist): " + e.getMessage());
        }
    }

    public <T> T execute(Callable<T> callable) {
        try {
            acquireLockWithCleanup();
            startHeartbeat();
            return callable.call();
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new FlywayException("Lock execution failed", e);
        } finally {
            stopHeartbeat();
            releaseLock();
        }
    }

    private void acquireLockWithCleanup() throws SQLException {
        // Step 1: Clean up any abandoned locks first
        cleanupAbandonedLocks();

        // Step 2: Try to acquire lock with timeout
        boolean acquired = tryAcquireLockWithTimeout();

        if (!acquired) {
            // Step 3: Check if lock is abandoned and force cleanup
            if (isLockAbandoned()) {
                LOG.warn("Detected abandoned lock (stale heartbeat), forcing cleanup for lock: " + lockNum);
                forceCleanupAbandonedLock();

                // Retry acquisition after cleanup
                acquired = tryAcquireLockWithTimeout();
            }
        }

        if (!acquired) {
            throw new FlywayException(
                "Unable to acquire PostgreSQL advisory lock " + lockNum + " after " +
                lockTimeout + " seconds. Another Flyway instance may be running.\n" +
                "Check 'SELECT * FROM flyway_lock_metadata' to see lock holder details."
            );
        }

        // Step 4: Record lock metadata
        recordLockMetadata();
        lockHeld = true;
    }

    private boolean tryAcquireLockWithTimeout() throws SQLException {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = lockTimeout * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            // Try non-blocking lock acquisition
            boolean acquired = jdbcTemplate.queryForBoolean(
                "SELECT pg_try_advisory_lock(?)", lockNum
            );

            if (acquired) {
                LOG.info("Successfully acquired PostgreSQL advisory lock: " + lockNum);
                return true;
            }

            // Log who holds the lock for debugging
            try {
                Map<String, Object> lockHolder = jdbcTemplate.queryForMap(
                    "SELECT owner_id, hostname, acquired_at, last_heartbeat " +
                    "FROM flyway_lock_metadata WHERE lock_id = ?",
                    lockNum
                );
                LOG.debug("Lock held by: " + lockHolder);
            } catch (Exception e) {
                // Ignore - metadata may not exist
            }

            // Wait before retry (exponential backoff, max 2 seconds)
            long elapsed = System.currentTimeMillis() - startTime;
            long backoff = Math.min(2000, 100 * (elapsed / 1000 + 1));

            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FlywayException("Lock acquisition interrupted", e);
            }
        }

        return false;
    }

    private void cleanupAbandonedLocks() throws SQLException {
        // Delete metadata for locks with stale heartbeat
        int deleted = jdbcTemplate.update(
            "DELETE FROM flyway_lock_metadata " +
            "WHERE last_heartbeat < NOW() - INTERVAL '" + abandonedThreshold + " seconds'",
            new Object[0]
        );

        if (deleted > 0) {
            LOG.info("Cleaned up " + deleted + " abandoned lock(s) with stale heartbeat");
        }
    }

    private boolean isLockAbandoned() throws SQLException {
        try {
            Map<String, Object> metadata = jdbcTemplate.queryForMap(
                "SELECT lock_id, last_heartbeat, " +
                "EXTRACT(EPOCH FROM (NOW() - last_heartbeat)) as seconds_since_heartbeat " +
                "FROM flyway_lock_metadata WHERE lock_id = ?",
                lockNum
            );

            Number secondsSince = (Number) metadata.get("seconds_since_heartbeat");
            return secondsSince != null && secondsSince.doubleValue() > abandonedThreshold;
        } catch (Exception e) {
            // No metadata found
            return false;
        }
    }

    private void forceCleanupAbandonedLock() throws SQLException {
        // Get the abandoned lock info for logging
        try {
            Map<String, Object> lockInfo = jdbcTemplate.queryForMap(
                "SELECT owner_id, hostname, acquired_at, last_heartbeat, " +
                "EXTRACT(EPOCH FROM (NOW() - last_heartbeat)) as seconds_since_heartbeat " +
                "FROM flyway_lock_metadata WHERE lock_id = ?",
                lockNum
            );

            LOG.warn("Force cleaning abandoned lock: " + lockInfo);
        } catch (Exception e) {
            // Metadata may have been cleaned up by another process
        }

        // Delete the metadata
        jdbcTemplate.update(
            "DELETE FROM flyway_lock_metadata WHERE lock_id = ?",
            lockNum
        );

        // Try to terminate the abandoned session holding the advisory lock
        // This requires elevated permissions (pg_signal_backend role)
        tryTerminateAbandonedSession();
    }

    private void tryTerminateAbandonedSession() {
        try {
            // Find and terminate the session holding the advisory lock
            int terminated = jdbcTemplate.update(
                "SELECT COUNT(*) FROM pg_terminate_backend(pid) " +
                "FROM pg_locks l " +
                "JOIN pg_stat_activity a ON l.pid = a.pid " +
                "WHERE l.locktype = 'advisory' " +
                "AND l.classid = 0 " +
                "AND l.objid = ? " +
                "AND a.state != 'idle'",
                lockNum
            );

            if (terminated > 0) {
                LOG.info("Terminated " + terminated + " abandoned session(s) holding lock");
            }
        } catch (Exception e) {
            LOG.debug("Could not terminate abandoned session (may require elevated permissions): " +
                     e.getMessage());
            // Continue anyway - metadata cleanup is sufficient for detection
        }
    }

    private void recordLockMetadata() throws SQLException {
        jdbcTemplate.update(
            "INSERT INTO flyway_lock_metadata " +
            "(lock_id, owner_id, hostname, acquired_at, last_heartbeat, lock_type) " +
            "VALUES (?, ?, ?, NOW(), NOW(), 'MIGRATION') " +
            "ON CONFLICT (lock_id) DO UPDATE SET " +
            "owner_id = EXCLUDED.owner_id, " +
            "hostname = EXCLUDED.hostname, " +
            "acquired_at = EXCLUDED.acquired_at, " +
            "last_heartbeat = EXCLUDED.last_heartbeat, " +
            "lock_type = EXCLUDED.lock_type",
            lockNum, ownerId, hostname
        );
    }

    private void startHeartbeat() {
        heartbeatExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "Flyway-Lock-Heartbeat-" + lockNum);
            t.setDaemon(true); // Don't prevent JVM shutdown
            return t;
        });

        heartbeatExecutor.scheduleAtFixedRate(
            this::updateHeartbeat,
            heartbeatInterval,
            heartbeatInterval,
            TimeUnit.SECONDS
        );

        LOG.debug("Started heartbeat for lock " + lockNum + " with interval " + heartbeatInterval + "s");
    }

    private void updateHeartbeat() {
        if (!lockHeld) {
            return;
        }

        try {
            int updated = jdbcTemplate.update(
                "UPDATE flyway_lock_metadata SET last_heartbeat = NOW() " +
                "WHERE lock_id = ? AND owner_id = ?",
                lockNum, ownerId
            );

            if (updated == 0) {
                LOG.error("Lost lock metadata - another process may have cleaned it up. Lock: " + lockNum);
                lockHeld = false;
            } else {
                LOG.trace("Heartbeat updated for lock: " + lockNum);
            }
        } catch (Exception e) {
            LOG.error("Heartbeat update failed for lock " + lockNum + ": " + e.getMessage(), e);
            // Don't throw - let migration continue but log the issue
            // The lock will be detected as abandoned if heartbeat continues to fail
        }
    }

    private void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            try {
                if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.warn("Heartbeat executor did not terminate cleanly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void releaseLock() {
        try {
            // Delete metadata first
            int deleted = jdbcTemplate.update(
                "DELETE FROM flyway_lock_metadata WHERE lock_id = ? AND owner_id = ?",
                lockNum, ownerId
            );

            if (deleted > 0) {
                LOG.debug("Deleted lock metadata for: " + lockNum);
            }

            // Release advisory lock
            boolean released = jdbcTemplate.queryForBoolean(
                "SELECT pg_advisory_unlock(?)", lockNum
            );

            if (!released) {
                LOG.warn("Advisory lock " + lockNum + " was not held during unlock (this is unexpected)");
            } else {
                LOG.info("Successfully released PostgreSQL advisory lock: " + lockNum);
            }

            lockHeld = false;
        } catch (Exception e) {
            LOG.error("Failed to release lock " + lockNum + " cleanly: " + e.getMessage(), e);
        }
    }

    private String generateOwnerId() {
        // Unique identifier: UUID + PID + Thread
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String pid = getPid();
        String threadId = String.valueOf(Thread.currentThread().getId());
        return String.format("%s-%s-t%s", uuid, pid, threadId);
    }

    private String getPid() {
        try {
            // Format: "PID@hostname"
            String name = ManagementFactory.getRuntimeMXBean().getName();
            return name.split("@")[0];
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Returns current lock status for monitoring.
     */
    public LockStatus getLockStatus() {
        try {
            Map<String, Object> metadata = jdbcTemplate.queryForMap(
                "SELECT owner_id, hostname, acquired_at, last_heartbeat, " +
                "EXTRACT(EPOCH FROM (NOW() - last_heartbeat)) as seconds_since_heartbeat " +
                "FROM flyway_lock_metadata WHERE lock_id = ?",
                lockNum
            );

            return new LockStatus(
                true,
                (String) metadata.get("owner_id"),
                (String) metadata.get("hostname"),
                ((Timestamp) metadata.get("acquired_at")).toInstant(),
                ((Timestamp) metadata.get("last_heartbeat")).toInstant(),
                ((Number) metadata.get("seconds_since_heartbeat")).longValue()
            );
        } catch (Exception e) {
            return LockStatus.notHeld();
        }
    }

    public static class LockStatus {
        public final boolean held;
        public final String ownerId;
        public final String hostname;
        public final java.time.Instant acquiredAt;
        public final java.time.Instant lastHeartbeat;
        public final long secondsSinceHeartbeat;

        private LockStatus(boolean held, String ownerId, String hostname,
                          java.time.Instant acquiredAt, java.time.Instant lastHeartbeat,
                          long secondsSinceHeartbeat) {
            this.held = held;
            this.ownerId = ownerId;
            this.hostname = hostname;
            this.acquiredAt = acquiredAt;
            this.lastHeartbeat = lastHeartbeat;
            this.secondsSinceHeartbeat = secondsSinceHeartbeat;
        }

        public static LockStatus notHeld() {
            return new LockStatus(false, null, null, null, null, 0);
        }

        @Override
        public String toString() {
            if (!held) {
                return "Lock not held";
            }
            return String.format(
                "Lock held by %s@%s (acquired: %s, last heartbeat: %ds ago)",
                ownerId, hostname, acquiredAt, secondsSinceHeartbeat
            );
        }
    }
}
