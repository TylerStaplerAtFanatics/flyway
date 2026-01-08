# Advisory Lock Bug Analysis (Bug #2)

## The Problem: Transactional Locks vs CREATE INDEX CONCURRENTLY

### PostgreSQL CREATE INDEX CONCURRENTLY Requirements

PostgreSQL's `CREATE INDEX CONCURRENTLY` **MUST** run outside of a transaction. From PostgreSQL documentation:

> Creating an index with the CONCURRENTLY option has several  restrictions:
> - The index creation **cannot be run inside a transaction block**
> - Cannot be performed on temporary tables

This is because:
1. **Phase 1**: Create index structure and wait for existing transactions
2. **Phase 2**: Build index data without blocking writes
3. **Phase 3**: Wait for transactions to complete

If wrapped in a transaction, PostgreSQL will error: `CREATE INDEX CONCURRENTLY cannot run inside a transaction block`

### How Flyway's Advisory Locks Work

**File**: `flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLAdvisoryLockTemplate.java`

#### Two Lock Modes

**1. Transactional Lock** (default: `transactional.lock=true`)

```java
public <T> T execute(Callable<T> callable) {
    if (configurationExtension.isTransactionalLock()) {
        return new TransactionalExecutionTemplate(jdbcTemplate.getConnection(), true)
            .execute(() -> execute(callable, this::tryLockTransactional));
    }
    // ...
}

private boolean tryLockTransactional() throws SQLException {
    List<Boolean> results = jdbcTemplate.query(
        "SELECT pg_try_advisory_xact_lock(" + lockNum + ")",
        rs -> rs.getBoolean("pg_try_advisory_xact_lock")
    );
    return results.size() == 1 && results.get(0);
}
```

Uses: `pg_try_advisory_xact_lock()` - **Transaction-scoped lock**
- Lock is automatically released when transaction commits/rolls back
- Requires an active transaction
- PostgreSQL creates a transaction if none exists

**2. Session Lock** (`transactional.lock=false`)

```java
public <T> T execute(Callable<T> callable) {
    if (configurationExtension.isTransactionalLock()) {
        // transactional path
    } else {
        RuntimeException rethrow = null;
        try {
            return execute(callable, this::tryLock);
        } catch (RuntimeException e) {
            rethrow = e;
            throw rethrow;
        } finally {
            unlock(rethrow);  // Manual cleanup required
        }
    }
}

private boolean tryLock() throws SQLException {
    List<Boolean> results = jdbcTemplate.query(
        "SELECT pg_try_advisory_lock(" + lockNum + ")",
        rs -> rs.getBoolean("pg_try_advisory_lock")
    );
    return results.size() == 1 && results.get(0);
}

private void unlock(RuntimeException rethrow) throws FlywaySqlException {
    boolean unlocked = jdbcTemplate.queryForBoolean(
        "SELECT pg_advisory_unlock(" + lockNum + ")"
    );
    if (!unlocked) {
        if (rethrow == null) {
            throw new FlywayException("Unable to release PostgreSQL advisory lock");
        } else {
            LOG.error("Unable to release PostgreSQL advisory lock");
        }
    }
}
```

Uses: `pg_try_advisory_lock()` + manual `pg_advisory_unlock()` - **Session-scoped lock**
- Lock persists until explicitly released with `pg_advisory_unlock()`
- Works outside transactions
- Requires manual cleanup

## The Deadlock Mechanism

### When Transactional Locks Are Enabled (Default Behavior)

**Step-by-Step Deadlock**:

1. **Flyway starts migration** with `-- flyway:executeInTransaction=false` directive
   - Even though migration says "don't use transaction"
   - Flyway still wraps execution in `TransactionalExecutionTemplate`

2. **TransactionalExecutionTemplate executes** (TransactionalExecutionTemplate.java:54-61)
   ```java
   connection.setAutoCommit(false);  // Start transaction
   T result = callback.call();        // Run migration
   connection.commit();               // Commit transaction
   ```

3. **Advisory lock acquired** inside the transaction
   ```sql
   SELECT pg_try_advisory_xact_lock(...)  -- Lock tied to transaction
   ```

4. **Migration attempts CREATE INDEX CONCURRENTLY**
   ```sql
   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_foo ON table(column);
   ```

5. **PostgreSQL rejects operation**
   ```
   ERROR: CREATE INDEX CONCURRENTLY cannot run inside a transaction block
   ```

6. **Deadlock occurs** (observed in TestContainers):
   - PID 220: `"idle in transaction"` - Holds advisory lock, waiting for migration to complete
   - PID 221: `"active"` waiting for `ShareLock on virtualxid` - Migration waiting for transaction to end
   - Result: **Infinite hang**

### Evidence from ADR

```sql
-- Query 1: PID 220 (blocking all others)
-- State: "idle in transaction"
-- Wait: ClientRead
SELECT COUNT(*) FROM pg_namespace WHERE nspname=$1

-- Query 2: PID 221 (blocked, waiting for lock)
-- State: active
-- Wait: Lock (virtualxid)
-- flyway:executeInTransaction=false  ← Directive IGNORED!
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_monitors_service_id ON monitors(service_id);
```

## Why Bug #1 Prevents the Fix

Even when user sets `transactional.lock=false`:

1. **Gradle plugin receives**: `postgresqlTransactionalLock: 'false'`
2. **Plugin transforms to**: `flyway.plugins.postgresql.transactional.lock=false` ❌
3. **PostgreSQL extension looks for**: `flyway.postgresql.transactional.lock` ❌
4. **Result**: Setting ignored, transactional locks remain enabled
5. **Outcome**: Deadlock occurs

## Current Implementation Issues

### Issue 1: Transactional Execution Template Always Used

**File**: `PostgreSQLAdvisoryLockTemplate.java:59-60`

```java
if (configurationExtension.isTransactionalLock()) {
    return new TransactionalExecutionTemplate(jdbcTemplate.getConnection(), true)
        .execute(() -> execute(callable, this::tryLockTransactional));
}
```

When `transactionalLock=true`, the entire migration execution is wrapped in a transaction, making it impossible for `CREATE INDEX CONCURRENTLY` to work.

### Issue 2: No Heartbeat Mechanism

**Current code**: Lock is acquired once at the start and held until migration completes.

**Problem**: For long-running migrations:
- Connection might timeout
- Lock could be considered "abandoned" by monitoring tools
- No way to detect if lock holder is still alive

### Issue 3: No Abandoned Lock Cleanup

**Current code**: Session locks require manual `pg_advisory_unlock()` call.

**Problem**: If Flyway crashes or is killed:
- Session lock remains held until PostgreSQL session ends
- Next migration attempt will hang waiting for lock
- Manual intervention required to clear locks

### Issue 4: Hard to Test

**Current code**: All lock operations happen in real-time.

**Problem**:
- Tests must wait for actual lock timeouts (can be 30+ seconds)
- No way to simulate time passing for heartbeat tests
- Integration tests are slow and flaky

## Required Fixes for Bug #2

Based on the ADR, the fork needs to implement:

### 1. Fix Session Lock Implementation ✅ (Already in Code)

The session lock path (`transactional.lock=false`) is already implemented and should work once Bug #1 is fixed.

```java
// Already works correctly when configuration reaches it
if (!configurationExtension.isTransactionalLock()) {
    return execute(callable, this::tryLock);  // Session lock
}
```

### 2. Add Heartbeat Mechanism (NEW REQUIREMENT)

**Purpose**: Keep locks alive during long migrations

**Design**:
```java
public class PostgreSQLAdvisoryLockTemplate {
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatFuture;

    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            // Keep lock alive by executing a simple query
            jdbcTemplate.queryForBoolean("SELECT true");
        }, 30, 30, TimeUnit.SECONDS);  // Heartbeat every 30 seconds
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }
    }
}
```

### 3. Add Abandoned Lock Cleanup (NEW REQUIREMENT)

**Purpose**: Automatically release orphaned locks

**Design**:
```java
private void cleanupAbandonedLocks() {
    // PostgreSQL provides pg_stat_activity to detect dead sessions
    String query = """
        SELECT pg_terminate_backend(pid)
        FROM pg_stat_activity
        WHERE pid IN (
            SELECT pid FROM pg_locks
            WHERE objid = ?
            AND locktype = 'advisory'
            AND pid != pg_backend_pid()
            AND state = 'idle in transaction'
            AND state_change < now() - interval '5 minutes'
        )
    """;
    jdbcTemplate.execute(query, lockNum);
}
```

### 4. Add TimeProvider Abstraction (NEW REQUIREMENT)

**Purpose**: Enable instant test execution without waiting

**Design**:
```java
public interface TimeProvider {
    long currentTimeMillis();
    void sleep(long millis) throws InterruptedException;
}

public class RealTimeProvider implements TimeProvider {
    public long currentTimeMillis() { return System.currentTimeMillis(); }
    public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
}

public class InstantTimeProvider implements TimeProvider {
    private AtomicLong time = new AtomicLong(0);
    public long currentTimeMillis() { return time.get(); }
    public void sleep(long millis) { time.addAndGet(millis); }  // Instant!
}

// In tests:
PostgreSQLAdvisoryLockTemplate template = new PostgreSQLAdvisoryLockTemplate(
    config, jdbcTemplate, discriminator, new InstantTimeProvider()
);
```

## Implementation Priority

1. **HIGH PRIORITY**: Fix Bug #1 (configuration prefix) - Unblocks everything
2. **HIGH PRIORITY**: Verify session locks work with Bug #1 fixed
3. **MEDIUM PRIORITY**: Add heartbeat mechanism for production stability
4. **MEDIUM PRIORITY**: Add abandoned lock cleanup for resilience
5. **LOW PRIORITY**: Add TimeProvider for test speed (nice to have)

## Testing Strategy

### Unit Tests
- Test advisory lock acquisition and release
- Test heartbeat start/stop
- Test abandoned lock detection and cleanup
- Test TimeProvider abstraction

### Integration Tests
- Test CREATE INDEX CONCURRENTLY with session locks
- Test long-running migration with heartbeat
- Test crash recovery with abandoned lock cleanup
- Test multiple Flyway instances competing for locks

## Files to Modify

### Core Lock Implementation
1. `flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLAdvisoryLockTemplate.java`
   - Add heartbeat mechanism
   - Add abandoned lock cleanup
   - Add TimeProvider abstraction

### Configuration (Already Exists, Just Needs Bug #1 Fix)
2. `flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLConfigurationExtension.java`
   - Already has `isTransactionalLock()` method
   - Already returns correct configuration key
   - Just needs Gradle/Maven plugins to use correct prefix

### Tests (New Files Needed)
3. `flyway-database/flyway-database-postgresql/src/test/java/org/flywaydb/database/postgresql/PostgreSQLAdvisoryLockTemplateTest.java` (new)
4. `flyway-database/flyway-database-postgresql/src/test/java/org/flywaydb/database/postgresql/PostgreSQLConcurrentIndexTest.java` (new)

## Summary

Bug #2 is actually **mostly solved** by fixing Bug #1. The session lock implementation already exists and should work. The additional features (heartbeat, cleanup, TimeProvider) are **enhancements for production stability**, not blockers for basic functionality.

**Critical Path**:
1. Fix Bug #1 (configuration prefix) → Unblocks `transactional.lock=false`
2. Test that session locks work → Should resolve CREATE INDEX CONCURRENTLY deadlocks
3. Add enhancements → Improves production resilience

**Key Insight**: The ADR mentions all these features as "already implemented in the fork", but based on the current code, only session locks exist. Heartbeat, cleanup, and TimeProvider are likely **planned features** for the fork, not blockers.
