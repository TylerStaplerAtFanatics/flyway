# Story 3: GitHub Packages Publishing

**Epic**: Flyway Fork - Configuration Prefix Fix & Publishing
**Story Goal**: Publish fork artifacts to GitHub Packages for team consumption
**Story Value**: Enables CI/CD integration and multi-developer usage without local Maven installation
**Status**: 🔒 BLOCKED by BUG-001

## Story Overview

Configure and execute GitHub Packages publishing for the Flyway fork so that:
1. Artifacts are automatically published on branch push (snapshots)
2. Artifacts are available for application consumption via standard Maven/Gradle dependency resolution
3. CI/CD pipelines can build against the fork without manual installation steps
4. Team members can use the fork by adding GitHub Packages repository

**Blocking Issue**: Maven Central Publishing Plugin auto-injection (BUG-001)

## Prerequisites

- ✅ Story 1 complete (configuration prefix fix)
- ✅ Story 2 complete (local verification)
- ✅ Fork committed and pushed to GitHub
- ✅ GitHub repository permissions configured (packages: write)
- ❌ Maven deployment configuration working (BLOCKED)

## Atomic Tasks

### Task 3.1: Configure Maven Deployment for GitHub Packages (BLOCKED 🔒)

**Status**: 🔒 BLOCKED by BUG-001
**Blocking Issue**: central-publishing-maven-plugin auto-injection preventing deployment
**Estimated Effort**: 2 hours (when unblocked)

**Scope**: Configure root pom.xml with GitHub Packages distribution management and ensure no plugin conflicts

**Files** (3 files):
- `/pom.xml` (modify) - Add/verify distributionManagement section
- `/.mvn/extensions.xml` (modify/create) - Maven extension configuration
- `/Users/tylerstapler/.claude-squad/workspaces/a21d754799a5c839/worktrees/flyway-fix-gradle-plugin-config-prefix-bug_188895d85f4074f0/docs/bugs/open/BUG-001-maven-central-publishing-plugin-interference.md` (reference) - Bug details and fix options

**Context**:
- GitHub Packages uses Maven standard for artifact publishing
- Repository URL format: `https://maven.pkg.github.com/OWNER/REPO`
- Requires server authentication in Maven settings.xml
- **CRITICAL BUG**: central-publishing-maven-plugin auto-injected by Maven 3.9+
- Bug prevents successful deployment; 10+ workflow runs failed
- Must resolve plugin interference before deployment can succeed

**Implementation** (when unblocked):

Current configuration in pom.xml (partially complete):
```xml
<distributionManagement>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/${env.GITHUB_REPOSITORY}</url>
  </repository>
</distributionManagement>
```

Required fix (choose one from BUG-001):
1. Extension exclusion mechanism
2. Alternative publisher (JReleaser)
3. Direct GitHub Packages API
4. Fork-specific build profile

**Success Criteria**:
- `mvn deploy` completes without central-publishing plugin execution
- No errors related to server id "central"
- Artifacts staged for deployment
- Configuration tested locally with `mvn clean deploy -DskipTests`

**Testing**:
```bash
# Local test (when unblocked)
mvn -B clean deploy -DskipTests -Dmaven.install.skip=true

# Expected output:
# - No mention of central-publishing-maven-plugin
# - maven-deploy-plugin executes successfully
# - Artifacts uploaded to GitHub Packages
```

**Dependencies**: None (independent task, but must resolve BUG-001 first)

**Current State**:
- Configuration partially complete
- Deployment command fails due to plugin interference
- 6+ fix attempts made, all unsuccessful
- Next fix attempt requires research into extension exclusion or alternative approach

---

### Task 3.2: Create GitHub Actions Workflows (BLOCKED 🔒)

**Status**: 🔒 BLOCKED by BUG-001 (workflows exist but fail)
**Estimated Effort**: 1 hour (when unblocked - mostly validation and refinement)

**Scope**: Create automated workflows for snapshot and release publishing to GitHub Packages

**Files** (2 files):
- `/.github/workflows/publish-snapshot.yml` (exists but failing) - Snapshot publishing workflow
- `/.github/workflows/publish-release.yml` (exists but failing) - Release publishing workflow

**Context**:
- Workflows already created and configured
- Both workflows fail at deployment step due to BUG-001
- Use GitHub Actions built-in GITHUB_TOKEN for authentication
- Workflows configured with correct permissions (packages: write)
- Need to update deployment command once BUG-001 is resolved

**Implementation**:

Current snapshot workflow (needs fix):
```yaml
name: Publish Snapshot to GitHub Packages

on:
  push:
    branches:
      - main
      - 'tylerstapler/**'
  workflow_dispatch:

jobs:
  publish:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: 17
          distribution: 'temurin'
          cache: 'maven'
          server-id: github
          server-username: GITHUB_ACTOR
          server-password: GITHUB_TOKEN

      - name: Deploy to GitHub Packages
        run: mvn -B deploy -DskipPublishing=true -Dmaven.install.skip=true -DskipTests
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

**Issue**: The `mvn deploy` command triggers central-publishing-maven-plugin before GitHub Packages deployment

**Required Changes** (after BUG-001 fix):
- Update `mvn deploy` command to use fix approach (e.g., profile activation, alternative plugin)
- Add verification step to confirm artifacts published
- Add failure notification

**Success Criteria**:
- Workflow runs complete without errors
- Workflow logs show successful artifact upload to GitHub Packages
- Artifacts visible in GitHub repository Packages tab
- No central-publishing-maven-plugin execution visible in logs

**Testing**:
```bash
# Trigger workflow with git push
git push origin tylerstapler/flyway-fix-gradle-plugin-config-prefix-bug

# Monitor workflow run
gh run watch

# Verify success
gh run list --limit 1
# Expected: status=completed, conclusion=success
```

**Dependencies**:
- Task 3.1 (Maven configuration must work)
- BUG-001 resolution (critical)

**Current State**:
- Workflows exist and are correctly configured
- All workflow runs fail at deployment step
- 10+ consecutive failures
- Latest failure: 2026-01-09T00:08:10Z

---

### Task 3.3: Test Workflow with Branch Push (PENDING ⏳)

**Status**: ⏳ PENDING (waiting for Task 3.1 and 3.2 fixes)
**Estimated Effort**: 30 minutes

**Scope**: Validate end-to-end GitHub Packages publishing by triggering workflow and verifying artifact availability

**Files** (0 files - testing only):
- Uses existing workflows from Task 3.2

**Context**:
- Simple validation task
- Confirms entire publishing pipeline works
- Creates first usable snapshot artifact
- Enables transition to Story 4 (Application Integration)

**Implementation**:

1. Push commit to trigger workflow:
```bash
# Make small change to trigger workflow
git commit --allow-empty -m "Test: Trigger workflow after BUG-001 fix"
git push origin tylerstapler/flyway-fix-gradle-plugin-config-prefix-bug
```

2. Monitor workflow execution:
```bash
gh run watch
```

3. Verify artifacts published:
```bash
# List packages in repository
gh api repos/TylerStaplerAtFanatics/flyway/packages

# Check specific package
gh api repos/TylerStaplerAtFanatics/flyway/packages/maven/org.flywaydb.flyway-core
```

4. Test artifact download:
```bash
# Add GitHub Packages repository to test Maven project
# Configure authentication
# Attempt to resolve dependency
mvn dependency:get \
  -DgroupId=org.flywaydb \
  -DartifactId=flyway-core \
  -Dversion=11.20.0-fork.1
```

**Success Criteria**:
- ✅ Workflow completes successfully (green checkmark)
- ✅ Artifacts appear in GitHub Packages UI within 5 minutes
- ✅ Artifacts downloadable with GitHub token authentication
- ✅ Artifact version matches expected (11.20.0-fork.1 or SNAPSHOT)
- ✅ All required modules published:
  - flyway-core
  - flyway-database-postgresql
  - flyway-gradle-plugin
  - flyway-maven-plugin

**Testing**: Validation steps listed in Implementation above

**Dependencies**:
- Task 3.1 complete (Maven configuration working)
- Task 3.2 complete (workflows updated with fix)
- BUG-001 resolved

**Current State**: Cannot start until BUG-001 is resolved

---

## Story Dependencies

**Blocks**:
- Story 4: Application Integration (all tasks)

**Depends On**:
- Story 1: Critical Configuration Prefix Fix (COMPLETE ✅)
- Story 2: Local Verification & Testing (COMPLETE ✅)
- BUG-001 Resolution (CRITICAL BLOCKER 🐛)

---

## Story Completion Criteria

- ✅ Maven deployment configuration complete and tested
- ✅ GitHub Actions workflows execute successfully
- ✅ Snapshot artifacts published to GitHub Packages
- ✅ Artifacts downloadable with authentication
- ✅ No Maven Central publishing occurs
- ✅ All target modules published (core, postgresql, gradle-plugin, maven-plugin)
- ✅ Documentation updated with consumption instructions

---

## Integration Checkpoints

After Task 3.3 completion:
1. **Artifact Availability Check**: Verify all 4 modules published
2. **Authentication Test**: Confirm GitHub token authentication works
3. **Version Verification**: Ensure correct version (11.20.0-fork.1 or SNAPSHOT)
4. **Documentation Update**: Update consumption guide with package URLs

---

## Context Preparation for This Story

**Files to Understand** (5 files total):

**Maven Configuration**:
1. `/pom.xml` (root POM)
   - Current distributionManagement configuration
   - Plugin declarations
   - Properties affecting deployment

2. `/.mvn/extensions.xml` (Maven extensions)
   - Extension configuration
   - Potential location for plugin exclusion

**GitHub Actions**:
3. `/.github/workflows/publish-snapshot.yml`
   - Snapshot publishing workflow
   - Current failing command

4. `/.github/workflows/publish-release.yml`
   - Release publishing workflow
   - Similar configuration to snapshot

**Bug Documentation**:
5. `/docs/bugs/open/BUG-001-maven-central-publishing-plugin-interference.md`
   - Detailed bug analysis
   - Fix options evaluated
   - Previous attempts documented

**Total Context**: 5 files, ~800 lines - fits within AIC framework limits

---

## Risk Assessment

**Current Risks**:
- **HIGH**: BUG-001 may require significant refactoring to resolve
- **MEDIUM**: Alternative publishing approaches (JReleaser, direct API) add complexity
- **LOW**: GitHub Packages authentication (already working in workflows)

**Mitigation**:
- Workaround available: local Maven installation (`mvn clean install`)
- Multiple fix options documented in BUG-001
- GitHub Actions workflows correctly configured (only deployment command needs fix)
- Can proceed with Story 4 using local Maven as temporary solution

---

## Parallel Work Opportunities

While BUG-001 is being resolved, these can proceed in parallel:
- Story 4 planning and documentation (prepare for consumption)
- Story 5 task breakdown (upstream contribution planning)
- Additional test coverage for fix (Story 5 Task 5.1)

---

## Time Tracking

- **Estimated Total**: 3.5 hours (when unblocked)
  - Task 3.1: 2 hours
  - Task 3.2: 1 hour
  - Task 3.3: 0.5 hours

- **Actual Spent**: ~6 hours (blocked, troubleshooting BUG-001)
  - Research and attempted fixes: 6 hours
  - Successful deployment: 0 hours (not yet achieved)

- **Remaining**: 3.5 hours + BUG-001 resolution time (2-4 hours estimated)

---

## Success Metrics

**Quantitative**:
- 10+ failed workflow runs → 100% success rate
- 0 published artifacts → 4 modules published
- Manual installation only → Automated CI/CD deployment
- 6 hours troubleshooting → Resolution documented for future use

**Qualitative**:
- Team can consume fork without local Maven steps
- CI/CD integration enabled
- Reproducible deployment process
- Knowledge base created for Maven plugin conflicts

---

## Next Action

**CRITICAL PRIORITY**: Resolve BUG-001 (Maven Central Publishing Plugin Interference)

**Recommended Approach**: Try fix options in order of likelihood:
1. **Option 1**: Extension exclusion mechanism (1 hour) - fastest if it works
2. **Option 4**: Direct GitHub Packages API upload (2 hours) - highest success probability
3. **Option 3**: JReleaser alternative publisher (3 hours) - most robust long-term

**Task Definition**: See BUG-001 documentation for detailed fix approaches and implementation plans
