# Research Question

## ACTUAL PROBLEM (Updated 2026-01-07)
**Fix advisory lock deadlocks in Flyway that prevent `CREATE INDEX CONCURRENTLY` from working in PostgreSQL migrations.**

## Context
Branch: `tylerstapler/flyway-fix-gradle-plugin-config-prefix-bug`

**The Real Problem**: 56 of 142 database migrations use `CREATE INDEX CONCURRENTLY` to avoid blocking production writes. These migrations cause deadlocks in Flyway, hanging integration tests indefinitely and risking production deployment failures.

**Comprehensive Solution Already Exists**: A fork has been created at https://github.com/TylerStaplerAtFanatics/flyway with all fixes implemented (branch: `fix/gradle-plugin-config-prefix-bug`).

**Current Blocker**: Fork not yet published to Maven repository or integrated into build.

## Initial Findings

### Location
- **Primary File**: `flyway-plugins/flyway-gradle-plugin/src/main/java/org/flywaydb/gradle/task/AbstractFlywayTask.java`
- **Method**: `getPluginConfiguration()` (lines 834-857)
- **Affected Code**: Lines 845-846, 851-852

### Suspected Issue
The `getPluginConfiguration` method converts camelCase plugin configuration keys to lowercase dot-separated format and prepends `FLYWAY_PLUGINS_PREFIX` ("flyway.plugins.").

Current transformation:
```java
conf.put(FLYWAY_PLUGINS_PREFIX + String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT),
         configValue);
```

Where `FLYWAY_PLUGINS_PREFIX = "flyway.plugins."` (from ConfigUtils.java:153)

### Potential Problems
1. Double prefixing if keys already contain "flyway.plugins."
2. Missing prefix if keys are expected to already be prefixed
3. Incorrect transformation of nested camelCase keys
4. Mismatch with how configuration is expected to be consumed

## The Complete Picture

### Two Separate Bugs (Both Fixed in Fork)

**Bug 1: Gradle Plugin Configuration Prefix** (PRIMARY ROOT CAUSE)
- Gradle plugin adds wrong prefix: `flyway.plugins.postgresql.transactional.lock` instead of `flyway.postgresql.transactional.lock`
- Result: `FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK=false` is completely ignored
- Flyway holds transactional locks even when told not to

**Bug 2: Advisory Lock Design Issues**
- Flyway uses transaction-scoped advisory locks that conflict with CONCURRENTLY operations
- PostgreSQL requires CONCURRENTLY to run outside transactions
- Deadlock cycle: Migration waits for lock, lock holder waits for migration

### Fork Fixes (Already Implemented)

The fork at https://github.com/TylerStaplerAtFanatics/flyway includes:
1. ✅ Gradle plugin configuration prefix bug fix
2. ✅ Session-scoped advisory locks (instead of transaction-scoped)
3. ✅ Heartbeat mechanism for long migrations
4. ✅ Abandoned lock cleanup
5. ✅ TimeProvider abstraction for instant test execution

## Research Goals (REVISED)

**PRIMARY GOAL**: Integrate the fork into the build system

Since the fork already contains all fixes, research should focus on:
1. **Verify fork completeness** - Ensure all fixes are properly tested
2. **Publishing strategy** - GitHub Packages vs Artifactory vs local Maven repo
3. **Build integration** - Update build.gradle to use fork
4. **Testing verification** - Confirm integration tests no longer deadlock
5. **Documentation** - Ensure ADR and build docs are complete
6. **Upstream contribution** - Prepare pull request for Flyway maintainers

**SECONDARY GOAL**: Document the bugs for upstream contribution

The research already started is valuable for:
- Creating a detailed bug report for Flyway maintainers
- Explaining the root cause to the team
- Supporting the pull request with evidence
