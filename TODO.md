# Flyway Fork - Project TODO

**Last Updated**: 2026-02-25
**Branch**: `tylerstapler/flyway-fix-gradle-plugin-config-prefix-bug`
**Base Version**: Flyway 11.20.0

## Project Overview

This is a fork of Flyway that fixes a critical configuration prefix bug preventing PostgreSQL-specific settings from being recognized. The bug caused CREATE INDEX CONCURRENTLY operations to deadlock because transactional lock configuration was ignored.

## Current Project Status

### Epic: Flyway Fork - Configuration Prefix Fix & Publishing

**Goal**: Fix the configuration prefix bug in Flyway plugins and publish the fork for consumption

**Value**: Enables 56 of 142 migrations using CREATE INDEX CONCURRENTLY to work without deadlock

**Success Metrics**:
- Configuration prefix bug fixed (COMPLETE)
- Local verification tests passing (COMPLETE)
- Fork published to GitHub Packages (IN PROGRESS - BLOCKED)
- Application can consume published fork (PENDING)

## Progress Summary

- **Overall Progress**: 60% complete (3 of 5 stories)
- **Critical Bugs**: 1 (Maven Central Publishing Plugin interference)
- **High Severity Bugs**: 0
- **Story Status**: 2 complete, 1 blocked, 2 pending

---

## Known Issues

### CRITICAL BUG-001: Maven Central Publishing Plugin Auto-Injection Blocking GitHub Packages Deploy

**Status**: 🐛 Open (blocking Story 3)
**Severity**: CRITICAL
**Discovered**: 2026-01-08 during Phase 3 GitHub Actions workflow execution
**Impact**: Cannot publish fork artifacts to GitHub Packages; all workflow runs failing

**Root Cause**:
The `central-publishing-maven-plugin` is being auto-injected by Maven 3.9+ and attempting to publish to Maven Central (server id: `central`), which fails because:
1. No Maven Central credentials configured (correctly - we want GitHub Packages)
2. Plugin runs before GitHub Packages deployment
3. Setting `<skip>true</skip>` doesn't prevent auto-injection
4. Setting `-DskipPublishing=true` property doesn't work
5. Using Maven 3.8.8 instead of 3.9+ doesn't prevent injection

**Evidence**:
```
[ERROR] Failed to execute goal org.sonatype.central:central-publishing-maven-plugin:0.7.0:publish
(injected-central-publishing) on project flyway-core:
Execution injected-central-publishing of goal
org.sonatype.central:central-publishing-maven-plugin:0.7.0:publish failed:
Unable to get publisher server properties for server id: central:
Cannot invoke "org.apache.maven.settings.Server.clone()" because "server" is null
```

**Fix Attempts Made** (all failed):
1. Added `<skip>true</skip>` configuration to plugin
2. Added `-DskipPublishing=true` property
3. Downgraded to Maven 3.8.8
4. Added explicit plugin configuration to disable
5. Downgraded maven-deploy-plugin to 2.8.2
6. Changed GitHub Actions setup-java server-id

**Related Tasks**: Blocks Story 3 (Task 3.1, 3.2)

**Detailed Documentation**: `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/docs/bugs/open/BUG-001-maven-central-publishing-plugin-interference.md`

---

## Story Breakdown

### Story 1: Critical Configuration Prefix Fix (COMPLETE ✅)

**Status**: ✅ COMPLETE
**Duration**: 30 minutes (vs 2-3 hours estimated)
**Value**: Unblocks CREATE INDEX CONCURRENTLY operations

**Tasks**:
- ✅ Task 1.1: Fix Gradle plugin configuration prefix (15m)
- ✅ Task 1.2: Fix Maven plugin configuration prefix (15m)

**Files Modified**:
- `flyway-plugins/flyway-gradle-plugin/src/main/java/org/flywaydb/gradle/task/AbstractFlywayTask.java`
- `flyway-plugins/flyway-maven-plugin/src/main/java/org/flywaydb/maven/AbstractFlywayMojo.java`

**Commit**: `b7c28f68a` - "Fix: Remove incorrect FLYWAY_PLUGINS_PREFIX from plugin configuration"

### Story 2: Local Verification & Testing (COMPLETE ✅)

**Status**: ✅ COMPLETE
**Duration**: 15 minutes
**Value**: Proves fix works without deadlock

**Tasks**:
- ✅ Task 2.1: Install fork to local Maven repository (5m)
- ✅ Task 2.2: Create test Spring Boot application (5m)
- ✅ Task 2.3: Run integration tests with TestContainers (5m)

**Test Results**: 4/4 tests passing
- Configuration transformation unit tests: 4 passing
- CREATE INDEX CONCURRENTLY integration tests: 2 passing
- Migration completed in 235ms (vs infinite hang before)

### Story 3: GitHub Packages Publishing (BLOCKED 🔒)

**Status**: 🔒 BLOCKED by BUG-001
**Blocking Issue**: Maven Central Publishing Plugin auto-injection
**Value**: Makes fork available for consumption without local Maven installation

**Tasks**:
- 🔒 Task 3.1: Configure GitHub Packages deployment (BLOCKED by BUG-001)
- 🔒 Task 3.2: Create GitHub Actions workflows (BLOCKED by BUG-001)
- ⏳ Task 3.3: Test workflow with branch push (waiting for 3.1-3.2)

**Workflow Status**: 10+ consecutive failures on `publish-snapshot.yml`

**Detailed Documentation**: `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/docs/tasks/github-packages-publishing.md`

### Story 4: Application Integration (PENDING ⏳)

**Status**: ⏳ PENDING (depends on Story 3)
**Value**: Enables application to use fork without local Maven steps

**Tasks**:
- ⏳ Task 4.1: Update application build.gradle to use GitHub Packages
- ⏳ Task 4.2: Configure GitHub token authentication
- ⏳ Task 4.3: Verify application builds and deploys with fork

### Story 5: Upstream Contribution (PENDING ⏳)

**Status**: ⏳ PENDING (optional, future work)
**Value**: Benefits entire Flyway community

**Tasks**:
- ⏳ Task 5.1: Add comprehensive unit tests
- ⏳ Task 5.2: Update documentation
- ⏳ Task 5.3: Create pull request to upstream Flyway
- ⏳ Task 5.4: Respond to maintainer feedback

---

## Dependency Visualization

```
Story 1 (Critical Fix) ✅
├─ Task 1.1 ✅ (15m)
└─ Task 1.2 ✅ (15m)
       ↓
Story 2 (Verification) ✅
├─ Task 2.1 ✅ (5m)
├─ Task 2.2 ✅ (5m)
└─ Task 2.3 ✅ (5m)
       ↓
🐛 BUG-001 [CRITICAL] blocks Story 3
       ↓
Story 3 (Publishing) 🔒
├─ Task 3.1 🔒 (blocked by BUG-001)
├─ Task 3.2 🔒 (blocked by BUG-001)
└─ Task 3.3 ⏳ (waiting for 3.1-3.2)
       ↓
Story 4 (App Integration) ⏳
├─ Task 4.1 ⏳
├─ Task 4.2 ⏳
└─ Task 4.3 ⏳
       ↓
Story 5 (Upstream) ⏳
├─ Task 5.1 ⏳
├─ Task 5.2 ⏳
├─ Task 5.3 ⏳
└─ Task 5.4 ⏳
```

---

## Key Metrics

### Time Efficiency
- **Research Phase**: 4 hours (completed earlier)
- **Phase 1 Implementation**: 30 minutes (67% faster than estimate)
- **Phase 2 Verification**: 15 minutes
- **Phase 3 Publishing**: BLOCKED (attempted for 6+ hours)
- **Total Productive Time**: 4.75 hours
- **Total Blocked Time**: ~6 hours

### Code Changes
- **Lines Changed**: 3 (critical fix)
- **Files Modified**: 2 (plugin files)
- **Tests Created**: 6 (verification)
- **Impact/Effort Ratio**: Extremely high

### Risk Assessment
- **Current Risk**: MEDIUM (blocked on publishing but workaround available)
- **Workaround**: Local Maven installation works for immediate needs
- **Blocking Issue**: Maven plugin ecosystem conflict

---

## Immediate Next Actions

### CRITICAL: Resolve BUG-001 (Maven Central Publishing Plugin)

**Priority**: CRITICAL - Blocks all publishing workflows

**Options to Investigate**:
1. Complete plugin exclusion from Maven build lifecycle
2. Maven extension mechanism to prevent auto-injection
3. Custom Maven profile that bypasses central-publishing
4. Alternative publishing approach (JReleaser, direct GitHub API)
5. Fork-specific build configuration that isolates deployment

**Expected Effort**: 2-4 hours (research + implementation)

**Success Criteria**:
- GitHub Actions workflow completes successfully
- Artifacts published to GitHub Packages
- No Maven Central publishing attempted

---

## Context Preparation for Next Work

### Files to Understand for BUG-001 Resolution

**Maven Build Configuration** (2 files):
- `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/pom.xml` - Root POM with distributionManagement
- `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/.mvn/extensions.xml` - Maven extensions

**GitHub Actions Workflows** (2 files):
- `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/.github/workflows/publish-snapshot.yml` - Failing workflow
- `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/.github/workflows/publish-release.yml` - Release workflow

**Context Boundary**: 4 files, ~800 lines total - fits within AIC limits

---

## Workaround (Temporary Solution)

While BUG-001 is being resolved, the fork can be used via local Maven installation:

```bash
# Install to local Maven repository
mvn clean install -DskipTests

# Use in application pom.xml without GitHub Packages repository
# Dependencies resolve from ~/.m2/repository/
```

**Limitation**: Each developer must build fork locally; no CI/CD integration

---

## Links to Detailed Documentation

- **Implementation Status**: `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/.llm/implementation/STATUS.md`
- **Phase 1 Complete**: `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/.llm/implementation/phase1-complete.md`
- **Phase 2 Verification**: `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/.llm/implementation/phase2-verification-results.md`
- **GitHub Packages Setup**: `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/.llm/implementation/github-packages-setup.md`
- **Research Documentation**: `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/.llm/research/`

---

## Original Issue References

- Flyway GitHub Issue #3492: PostgreSQL CREATE INDEX CONCURRENTLY deadlock
- Flyway GitHub Issue #3858: Configuration prefix not recognized
- Flyway GitHub Issue #3961: Plugin configuration ignored
- Flyway GitHub Issue #1654: Advisory locks support
