# Flyway Fork - Configuration Prefix Fix

This is a fork of [Flyway](https://github.com/flyway/flyway) that fixes a critical bug in the Gradle and Maven plugin configuration handling.

## What's Fixed

**Bug**: The Gradle and Maven plugins incorrectly add `flyway.plugins.` prefix to all plugin configuration keys, breaking ConfigurationExtension-based settings.

**Impact**: PostgreSQL-specific configuration (like `postgresqlTransactionalLock`) is ignored, causing CREATE INDEX CONCURRENTLY operations to deadlock.

**Fix**: Changed 3 lines of code to use `flyway.` prefix instead of `flyway.plugins.` prefix.

## The Problem

When you configure Flyway's PostgreSQL-specific settings:

```groovy
// Gradle
flyway {
    pluginConfiguration = [
        postgresqlTransactionalLock: 'false'
    ]
}
```

```xml
<!-- Maven -->
<plugin>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-maven-plugin</artifactId>
    <configuration>
        <pluginConfiguration>
            <postgresqlTransactionalLock>false</postgresqlTransactionalLock>
        </pluginConfiguration>
    </configuration>
</plugin>
```

### Before Fix ❌

The plugins transform this to:
```
postgresqlTransactionalLock → flyway.plugins.postgresql.transactional.lock
```

But PostgreSQL extension expects:
```
flyway.postgresql.transactional.lock
```

**Result**: Configuration ignored, transactional locks remain enabled, CREATE INDEX CONCURRENTLY deadlocks.

### After Fix ✅

The plugins now correctly transform to:
```
postgresqlTransactionalLock → flyway.postgresql.transactional.lock
```

**Result**: Configuration recognized, session locks used, CREATE INDEX CONCURRENTLY works.

## Files Changed

### Gradle Plugin
**File**: `flyway-plugins/flyway-gradle-plugin/src/main/java/org/flywaydb/gradle/task/AbstractFlywayTask.java`

**Lines**: 845, 851

```diff
- conf.put(FLYWAY_PLUGINS_PREFIX + transformedKey, value);
+ conf.put("flyway." + transformedKey, value);
```

### Maven Plugin
**File**: `flyway-plugins/flyway-maven-plugin/src/main/java/org/flywaydb/maven/AbstractFlywayMojo.java`

**Line**: 876

```diff
- conf.put(FLYWAY_PLUGINS_PREFIX + transformedKey, value);
+ conf.put("flyway." + transformedKey, value);
```

## Using This Fork

### From GitHub Packages

Add to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/OWNER/REPO</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <version>11.20.0</version>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
    <version>11.20.0</version>
  </dependency>
</dependencies>
```

See [GitHub Packages Setup](.llm/implementation/github-packages-setup.md) for detailed instructions.

### From Local Build

```bash
# Clone and build
git clone https://github.com/OWNER/flyway.git
cd flyway
git checkout tylerstapler/flyway-fix-gradle-plugin-config-prefix-bug

# Install to local Maven repository
mvn clean install -DskipTests \
  -pl flyway-core,flyway-database/flyway-database-postgresql,flyway-plugins/flyway-gradle-plugin,flyway-plugins/flyway-maven-plugin \
  -am

# Use in your project (no repository configuration needed)
```

## Verification

This fork includes comprehensive tests demonstrating the fix:

### Unit Tests
```bash
cd test-app/test-app
mvn test -Dtest=FlywayConfigurationComparisonTest
```

Shows side-by-side comparison of original vs fixed behavior.

### Integration Tests
```bash
cd test-app/test-app
mvn test -Dtest=FlywayMigrationTest
```

Verifies CREATE INDEX CONCURRENTLY works with TestContainers.

**Results**:
- ✅ All tests pass
- ✅ Migration completes in 235ms (no deadlock)
- ✅ Indexes created successfully

## Impact

This fix enables:
- ✅ PostgreSQL CREATE INDEX CONCURRENTLY without deadlock
- ✅ All ConfigurationExtension-based database settings
- ✅ Oracle, SQL Server, and other database-specific configurations
- ✅ Custom ConfigurationExtension implementations

## Documentation

Full research and implementation documentation available in `.llm/`:

- **Research**: `.llm/research/2026-01-07-gradle-plugin-config-prefix-bug/`
  - `SUMMARY.md` - Executive summary
  - `01-config-bug-analysis.md` - Configuration bug details
  - `02-advisory-lock-bug-analysis.md` - Advisory lock analysis
  - `IMPLEMENTATION-PLAN.md` - Implementation plan

- **Implementation**: `.llm/implementation/`
  - `STATUS.md` - Current status
  - `phase1-complete.md` - Phase 1 completion details
  - `phase2-verification-results.md` - Verification results
  - `github-packages-setup.md` - Publishing guide

## Upstream Contribution

This fix should be contributed back to upstream Flyway. The changes are:
- ✅ Minimal (3 lines)
- ✅ Non-breaking
- ✅ Well-tested
- ✅ Documented

Related upstream issues:
- #3492 - PostgreSQL deadlock with CREATE INDEX CONCURRENTLY
- #3858 - Configuration not propagated
- #3961 - Advisory locks
- #1654 - Lock timeout issues

## License

Same as upstream Flyway: [Apache License 2.0](LICENSE)

## Base Version

This fork is based on Flyway 11.20.0.

## Branch

- **Main branch**: Tracks upstream
- **Fix branch**: `tylerstapler/flyway-fix-gradle-plugin-config-prefix-bug`

## Contact

For questions about this fork, see the research and implementation documentation in `.llm/`.
