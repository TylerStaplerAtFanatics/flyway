# Flyway Fork Implementation Plan

**Date**: 2026-01-07
**Branch**: `tylerstapler/flyway-fix-gradle-plugin-config-prefix-bug`
**Base Version**: Flyway 11.20.0

## Executive Summary

This plan outlines the implementation strategy for fixing two critical bugs that prevent PostgreSQL `CREATE INDEX CONCURRENTLY` from working in Flyway:

1. **Bug #1 (BLOCKER)**: Gradle/Maven plugin configuration prefix bug
2. **Bug #2 (DEPENDENT)**: Advisory lock design improvements

**Key Insight**: Bug #1 is the root cause blocker. Bug #2 is mostly solved by fixing Bug #1, with optional enhancements for production stability.

## Phased Approach

### Phase 1: Critical Fix (BLOCKER) - 2-3 hours
**Goal**: Unblock CREATE INDEX CONCURRENTLY by fixing configuration prefix bug

#### Changes Required
1. **Gradle Plugin** (`AbstractFlywayTask.java`)
   - Line 845: Change `FLYWAY_PLUGINS_PREFIX` to `"flyway."`
   - Line 851: Change `FLYWAY_PLUGINS_PREFIX` to `"flyway."`

2. **Maven Plugin** (`AbstractFlywayMojo.java`)
   - Line 876: Change `FLYWAY_PLUGINS_PREFIX` to `"flyway."`

#### Testing
- Manual testing with integration tests
- Verify `postgresqlTransactionalLock: 'false'` works
- Confirm CREATE INDEX CONCURRENTLY no longer deadlocks

#### Success Criteria
✅ Integration tests complete without deadlock
✅ All 56 CONCURRENTLY migrations pass
✅ Configuration reaches PostgreSQLConfigurationExtension correctly

### Phase 2: Verification (REQUIRED) - 1 hour
**Goal**: Ensure session locks work correctly with Bug #1 fixed

#### Testing
- Run full integration test suite
- Verify migrations with `-- flyway:executeInTransaction=false` work
- Check PostgreSQL `pg_locks` to confirm session-scoped locks used
- Test multiple concurrent Flyway instances

#### Success Criteria
✅ Session locks acquired and released correctly
✅ No deadlocks with CREATE INDEX CONCURRENTLY
✅ Lock contention handled gracefully

### Phase 3: Production Enhancements (OPTIONAL) - 4-6 hours
**Goal**: Add heartbeat, cleanup, and testing improvements

#### 3A: Heartbeat Mechanism (2 hours)
**Purpose**: Keep locks alive during long migrations

**Implementation**:
- Add `ScheduledExecutorService` to `PostgreSQLAdvisoryLockTemplate`
- Start heartbeat thread on lock acquisition
- Execute keepalive query every 30 seconds
- Stop heartbeat on lock release

**Files**:
- `PostgreSQLAdvisoryLockTemplate.java`

#### 3B: Abandoned Lock Cleanup (2 hours)
**Purpose**: Automatically release orphaned locks

**Implementation**:
- Query `pg_stat_activity` for stale sessions
- Terminate backends holding locks > 5 minutes idle
- Log cleanup actions for visibility

**Files**:
- `PostgreSQLAdvisoryLockTemplate.java`

#### 3C: TimeProvider Abstraction (1-2 hours)
**Purpose**: Enable instant test execution

**Implementation**:
- Create `TimeProvider` interface
- Implement `RealTimeProvider` for production
- Implement `InstantTimeProvider` for tests
- Inject into lock template

**Files**:
- `TimeProvider.java` (new)
- `RealTimeProvider.java` (new)
- `InstantTimeProvider.java` (new)
- `PostgreSQLAdvisoryLockTemplate.java` (modified)

### Phase 4: Testing & Documentation (REQUIRED) - 2-3 hours
**Goal**: Comprehensive test coverage and documentation

#### Test Coverage
1. **Unit Tests** (new files)
   - `PostgreSQLAdvisoryLockTemplateTest.java`
   - `AbstractFlywayTaskTest.java`
   - `AbstractFlywayMojoTest.java`

2. **Integration Tests** (new files)
   - `PostgreSQLConcurrentIndexTest.java`
   - Test CREATE INDEX CONCURRENTLY end-to-end
   - Test lock contention scenarios
   - Test crash recovery

#### Documentation
1. Update inline code comments
2. Document configuration properties
3. Create migration guide for users
4. Update README with changes

## Detailed File Changes

### Critical Changes (Phase 1)

#### 1. `flyway-plugins/flyway-gradle-plugin/src/main/java/org/flywaydb/gradle/task/AbstractFlywayTask.java`

**Line 845** (in `getPluginConfiguration` method):
```java
// BEFORE
conf.put(FLYWAY_PLUGINS_PREFIX + String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT),
         extensionPluginConfiguration.get(key));

// AFTER
conf.put("flyway." + String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT),
         extensionPluginConfiguration.get(key));
```

**Line 851** (in `getPluginConfiguration` method):
```java
// BEFORE
conf.put(FLYWAY_PLUGINS_PREFIX + String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT),
         pluginConfiguration.get(key));

// AFTER
conf.put("flyway." + String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT),
         pluginConfiguration.get(key));
```

#### 2. `flyway-plugins/flyway-maven-plugin/src/main/java/org/flywaydb/maven/AbstractFlywayMojo.java`

**Line 876** (in `getPluginConfiguration` method):
```java
// BEFORE
conf.put(FLYWAY_PLUGINS_PREFIX + String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT),
         pluginConfiguration.get(key));

// AFTER
conf.put("flyway." + String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT),
         pluginConfiguration.get(key));
```

### Optional Changes (Phase 3)

#### 3. `flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLAdvisoryLockTemplate.java`

**Add heartbeat fields**:
```java
private final TimeProvider timeProvider;
private ScheduledExecutorService heartbeatExecutor;
private ScheduledFuture<?> heartbeatFuture;
```

**Add heartbeat methods**:
```java
private void startHeartbeat() {
    heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "flyway-advisory-lock-heartbeat");
        t.setDaemon(true);
        return t;
    });

    heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
        try {
            // Keep connection alive
            jdbcTemplate.queryForBoolean("SELECT true");
            LOG.debug("Advisory lock heartbeat sent for lock " + lockNum);
        } catch (SQLException e) {
            LOG.error("Failed to send advisory lock heartbeat", e);
        }
    }, 30, 30, TimeUnit.SECONDS);
}

private void stopHeartbeat() {
    if (heartbeatFuture != null) {
        heartbeatFuture.cancel(true);
    }
    if (heartbeatExecutor != null) {
        heartbeatExecutor.shutdown();
        try {
            if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

**Add cleanup method**:
```java
private void cleanupAbandonedLocks() {
    try {
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

        int terminated = jdbcTemplate.execute(query, lockNum);
        if (terminated > 0) {
            LOG.warn("Terminated " + terminated + " abandoned lock holders for lock " + lockNum);
        }
    } catch (SQLException e) {
        LOG.debug("Unable to cleanup abandoned locks", e);
    }
}
```

**Modify execute method**:
```java
public <T> T execute(Callable<T> callable) {
    PostgreSQLConfigurationExtension configurationExtension =
        configuration.getPluginRegister().getExact(PostgreSQLConfigurationExtension.class);

    if (configurationExtension.isTransactionalLock()) {
        return new TransactionalExecutionTemplate(jdbcTemplate.getConnection(), true)
            .execute(() -> execute(callable, this::tryLockTransactional));
    } else {
        RuntimeException rethrow = null;
        try {
            cleanupAbandonedLocks();  // NEW: Clean before acquiring
            startHeartbeat();  // NEW: Start heartbeat
            return execute(callable, this::tryLock);
        } catch (RuntimeException e) {
            rethrow = e;
            throw rethrow;
        } finally {
            stopHeartbeat();  // NEW: Stop heartbeat
            unlock(rethrow);
        }
    }
}
```

### Test Files (Phase 4)

#### 4. `flyway-plugins/flyway-gradle-plugin/src/test/java/org/flywaydb/gradle/task/AbstractFlywayTaskTest.java` (NEW)

```java
public class AbstractFlywayTaskTest {
    @Test
    public void testPluginConfigurationTransformation() {
        Map<String, String> input = Map.of(
            "postgresqlTransactionalLock", "false",
            "someOtherSetting", "value"
        );

        Map<String, String> result = getPluginConfiguration(input);

        assertEquals("false", result.get("flyway.postgresql.transactional.lock"));
        assertEquals("value", result.get("flyway.some.other.setting"));
    }
}
```

#### 5. `flyway-database/flyway-database-postgresql/src/test/java/org/flywaydb/database/postgresql/PostgreSQLConcurrentIndexTest.java` (NEW)

```java
@TestContainers
public class PostgreSQLConcurrentIndexTest {
    @Container
    private static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Test
    public void testCreateIndexConcurrentlyWithSessionLocks() {
        Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .pluginConfiguration(Map.of("postgresqlTransactionalLock", "false"))
            .load();

        MigrateResult result = flyway.migrate();

        assertTrue(result.success);
        assertEquals(0, result.migrationsExecuted); // Or expected count
    }
}
```

## Implementation Order

### Week 1: Critical Path
1. **Day 1-2**: Implement Phase 1 (Critical Fix)
   - Modify Gradle plugin
   - Modify Maven plugin
   - Basic manual testing

2. **Day 3**: Implement Phase 2 (Verification)
   - Run integration tests
   - Verify no deadlocks
   - Test lock behavior

### Week 2: Enhancements & Polish
3. **Day 4-5**: Implement Phase 3A & 3B (Heartbeat & Cleanup)
   - Add heartbeat mechanism
   - Add abandoned lock cleanup
   - Unit tests for new features

4. **Day 6**: Implement Phase 3C (TimeProvider)
   - Create abstraction
   - Update lock template
   - Update tests

5. **Day 7**: Implement Phase 4 (Testing & Docs)
   - Write comprehensive tests
   - Update documentation
   - Final verification

## Success Criteria

### Phase 1 Complete
- ✅ Configuration prefix bug fixed in both plugins
- ✅ `postgresqlTransactionalLock: 'false'` reaches PostgreSQLConfigurationExtension
- ✅ Integration tests run without deadlock

### Phase 2 Complete
- ✅ Session locks work correctly
- ✅ All 56 CREATE INDEX CONCURRENTLY migrations pass
- ✅ No lock contention issues

### Phase 3 Complete (Optional)
- ✅ Heartbeat keeps locks alive during long migrations
- ✅ Abandoned locks automatically cleaned up
- ✅ Tests run instantly with InstantTimeProvider

### Phase 4 Complete
- ✅ Test coverage >80% for changed code
- ✅ Documentation updated
- ✅ Ready for upstream pull request

## Rollback Plan

If Phase 3 enhancements cause issues:
1. Revert to Phase 2 (session locks only)
2. Remove heartbeat/cleanup features
3. Keep TimeProvider for testing benefits

## Upstream Contribution

After all phases complete:
1. Squash commits into logical units
2. Write comprehensive commit messages
3. Create pull request to flyway/flyway
4. Include:
   - Bug description with evidence
   - Fix explanation
   - Test coverage
   - Performance impact analysis

## Notes

- **Critical Path**: Phases 1-2 are required for basic functionality
- **Enhancements**: Phase 3 is optional for production stability
- **Testing**: Phase 4 is required before upstream contribution
- **Timeline**: 5-7 days for complete implementation
