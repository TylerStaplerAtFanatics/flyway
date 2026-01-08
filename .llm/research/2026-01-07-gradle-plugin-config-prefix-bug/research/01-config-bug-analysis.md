# Configuration Bug Analysis

## The Bug: Gradle Plugin Adds Wrong Prefix

### Expected Behavior (from documentation)

**File**: `documentation/Reference/Configuration/Flyway Namespace/Flyway PostgreSQL Namespace/Flyway PostgreSQL Transactional Lock Setting.md`

```groovy
flyway {
    pluginConfiguration = [
      postgresqlTransactionalLock: 'false'
    ]
}
```

Should produce configuration key: `flyway.postgresql.transactional.lock=false`

### Actual Behavior (from code)

**File**: `flyway-plugins/flyway-gradle-plugin/src/main/java/org/flywaydb/gradle/task/AbstractFlywayTask.java:845`

```java
conf.put(FLYWAY_PLUGINS_PREFIX + String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT),
         extensionPluginConfiguration.get(key));
```

Where `FLYWAY_PLUGINS_PREFIX = "flyway.plugins."` (ConfigUtils.java:153)

**Result**:
- Input: `postgresqlTransactionalLock`
- Split by camelCase: `["postgresql", "Transactional", "Lock"]`
- Join with dots and lowercase: `"postgresql.transactional.lock"`
- Add prefix: `"flyway.plugins.postgresql.transactional.lock"` ❌ **WRONG**

**Expected**:
- Should produce: `"flyway.postgresql.transactional.lock"` ✅ **CORRECT**

## PostgreSQL Configuration Extension

**File**: `flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLConfigurationExtension.java:27`

```java
private static final String TRANSACTIONAL_LOCK = "flyway.postgresql.transactional.lock";
```

The PostgreSQL extension expects the property **WITHOUT** the `plugins` segment.

**Namespace**: `"postgresql"` (line 50)

This means the full property path should be constructed as:
`flyway.{namespace}.{property}` = `flyway.postgresql.transactional.lock`

## Environment Variable Mapping

**File**: `PostgreSQLConfigurationExtension.java:41-46`

```java
@Override
public String getConfigurationParameterFromEnvironmentVariable(String environmentVariable) {
    if ("FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK".equals(environmentVariable)) {
        return TRANSACTIONAL_LOCK; // Returns "flyway.postgresql.transactional.lock"
    }
    return null;
}
```

Environment variables work correctly because they bypass the plugin's transformation and go directly through ConfigUtils.

## Impact

**When using Gradle plugin**:
- User sets: `postgresqlTransactionalLock: 'false'`
- Plugin creates: `flyway.plugins.postgresql.transactional.lock=false`
- PostgreSQL extension looks for: `flyway.postgresql.transactional.lock`
- Result: **Setting is completely ignored, transactional locks remain enabled**

**When using environment variable**:
- User sets: `FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK=false`
- ConfigUtils converts to: `flyway.postgresql.transactional.lock=false`
- PostgreSQL extension finds it correctly
- Result: **Should work** (but may still have advisory lock issues)

## Maven Plugin Comparison

**File**: `flyway-plugins/flyway-maven-plugin/src/main/java/org/flywaydb/maven/AbstractFlywayMojo.java:876`

Maven plugin has **IDENTICAL BUG**:

```java
conf.put(FLYWAY_PLUGINS_PREFIX + String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT),
         pluginConfiguration.get(key));
```

Both plugins incorrectly add `flyway.plugins.` prefix to ALL plugin configuration keys.

## Root Cause

The bug is in the assumption that plugin configurations should be prefixed with `flyway.plugins.{namespace}.{property}`.

**Correct behavior**: Plugin configurations from `pluginConfiguration` map should be prefixed with `flyway.{namespace}.{property}` only, where namespace comes from the ConfigurationExtension.

The `FLYWAY_PLUGINS_PREFIX` constant should NOT be used for ConfigurationExtension properties. It's only for deprecated legacy plugin configurations.

## Evidence from ConfigUtils.java

Lines 390-396 show that `FLYWAY_PLUGINS` environment variables are **deprecated**:

```java
if (key.startsWith("FLYWAY_PLUGINS") && !DEPRECATED_PLUGINS_WARNED.contains(key)) {
    LOG.warn("Deprecated property configured through environment variable: '"
        + key
        + "'. Please see "
        + FlywayDbWebsiteLinks.V10_BLOG);
    DEPRECATED_PLUGINS_WARNED.add(key);
}
```

This suggests that the `flyway.plugins.` prefix is for a deprecated configuration mechanism, NOT for ConfigurationExtension-based configurations.

## The Fix

Both `AbstractFlywayTask.java` (Gradle) and `AbstractFlywayMojo.java` (Maven) need to:

1. **Remove** the `FLYWAY_PLUGINS_PREFIX` from the configuration key construction
2. Instead, use `"flyway."` + the transformed key

**Current (wrong)**:
```java
conf.put(FLYWAY_PLUGINS_PREFIX + transformedKey, value);
```

**Fixed (correct)**:
```java
conf.put("flyway." + transformedKey, value);
```

This will make `postgresqlTransactionalLock` → `flyway.postgresql.transactional.lock` ✅
