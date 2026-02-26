# BUG-001: Maven Central Publishing Plugin Auto-Injection Blocking GitHub Packages Deploy [SEVERITY: Critical]

**Status**: 🐛 Open
**Discovered**: 2026-01-08 during GitHub Actions workflow execution
**Impact**: Cannot publish fork artifacts to GitHub Packages; all deployment workflows failing

## Summary

The `central-publishing-maven-plugin` version 0.7.0 is being auto-injected by Maven 3.9+ build lifecycle and attempts to publish to Maven Central (server id: `central`) before GitHub Packages deployment can occur. This causes workflow failures because:

1. No Maven Central credentials are configured (correctly - we want GitHub Packages only)
2. Plugin execution happens automatically even when explicitly disabled
3. All 10+ GitHub Actions workflow runs have failed with identical error
4. Multiple attempted fixes have failed to prevent auto-injection

## Reproduction

**Workflow**: `.github/workflows/publish-snapshot.yml`

**Trigger**: Push to `tylerstapler/**` branch

**Command**:
```bash
mvn -B deploy -DskipPublishing=true -Dmaven.install.skip=true -DskipTests
```

**Expected**: Deploy to GitHub Packages at `maven.pkg.github.com/TylerStaplerAtFanatics/flyway`

**Actual**: Failure before GitHub Packages deployment

## Error Output

```
[ERROR] Failed to execute goal org.sonatype.central:central-publishing-maven-plugin:0.7.0:publish
(injected-central-publishing) on project flyway-core:
Execution injected-central-publishing of goal
org.sonatype.central:central-publishing-maven-plugin:0.7.0:publish failed:
Unable to get publisher server properties for server id: central:
Cannot invoke "org.apache.maven.settings.Server.clone()" because "server" is null -> [Help 1]
```

**Key Indicators**:
- Goal execution ID: `injected-central-publishing` (auto-injected, not in pom.xml)
- Plugin version: `0.7.0` (latest, auto-selected by Maven)
- Server lookup: `central` (Maven Central, not our GitHub Packages server)
- Null pointer: No `<server>` configured for `central` in settings.xml

## Root Cause Analysis

### Maven 3.9+ Auto-Injection Mechanism

Maven 3.9+ includes aggressive plugin auto-injection for "modernizing" deployments:

1. **Build extension auto-activation**: Maven automatically activates the `central-publishing` extension
2. **Plugin goal injection**: The plugin injects its `publish` goal into the `deploy` phase
3. **Execution happens before explicit deploy**: Injected goal runs before configured deployment
4. **Configuration ignored**: Standard plugin `<skip>true</skip>` configuration doesn't prevent injection
5. **Property overrides fail**: `-DskipPublishing` property doesn't disable injected execution

### Why This Breaks GitHub Packages Publishing

**Build Phase Order**:
```
1. maven-deploy-plugin:deploy (configured for GitHub Packages)
   └─ Blocked by step 2 failure
2. central-publishing-maven-plugin:publish (INJECTED, runs first)
   └─ Looks for server id: central
   └─ Not found in settings.xml
   └─ Throws NullPointerException
   └─ BUILD FAILURE
```

**Settings.xml Configuration** (GitHub Actions):
```xml
<servers>
  <server>
    <id>github</id>
    <username>${env.GITHUB_ACTOR}</username>
    <password>${env.GITHUB_TOKEN}</password>
  </server>
  <!-- NO <server id="central"> configured (correctly) -->
</servers>
```

The plugin expects a `<server id="central">` entry but there isn't one, nor should there be one for this fork.

## Files Affected (4 files)

### 1. `/pom.xml` (root POM)
**Role**: Defines distributionManagement and plugin configuration

**Current Configuration**:
```xml
<distributionManagement>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/${env.GITHUB_REPOSITORY}</url>
  </repository>
</distributionManagement>

<build>
  <plugins>
    <plugin>
      <groupId>org.sonatype.central</groupId>
      <artifactId>central-publishing-maven-plugin</artifactId>
      <version>0.7.0</version>
      <configuration>
        <skip>true</skip>
        <skipPublishing>true</skipPublishing>
      </configuration>
    </plugin>
  </plugins>
</build>
```

**Problem**: Even with `<skip>true</skip>`, Maven 3.9+ auto-injects the plugin execution

### 2. `/.mvn/extensions.xml`
**Role**: Maven build extensions (currently may be empty or standard)

**Potential Fix Location**: Could explicitly disable extensions here

### 3. `/.github/workflows/publish-snapshot.yml`
**Role**: GitHub Actions workflow for snapshot publishing

**Current Command**:
```yaml
- name: Deploy to GitHub Packages
  run: mvn -B deploy -DskipPublishing=true -Dmaven.install.skip=true -DskipTests
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

**Attempted Fix**: Added `-DskipPublishing=true` (didn't work)

### 4. `/.github/workflows/publish-release.yml`
**Role**: GitHub Actions workflow for release publishing

**Same Issue**: Will fail for same reason when triggered

## Fix Attempts Made (All Failed)

### Attempt 1: Skip Configuration
**Commit**: Unknown (early attempt)
**Change**: Added `<skip>true</skip>` to plugin configuration in pom.xml
**Result**: ❌ FAILED - Plugin still injected and executed

### Attempt 2: Skip Property
**Commit**: `a576221f7` - "Add skipPublishing property to disable central-publishing plugin"
**Change**: Added `-DskipPublishing=true` to Maven command
**Result**: ❌ FAILED - Property not recognized by injected execution

### Attempt 3: Maven Version Downgrade
**Commit**: `d4619502e` - "Downgrade to Maven 3.8.8 to avoid central-publishing auto-injection"
**Change**: Changed GitHub Actions to use Maven 3.8.8 instead of 3.9+
**Result**: ❌ FAILED - Still injected (extension may be in Maven 3.8.8 too)

### Attempt 4: Deploy Plugin Downgrade
**Commit**: `b6311905a` - "Downgrade maven-deploy-plugin to 2.8.2"
**Change**: Explicitly set maven-deploy-plugin version to older 2.8.2
**Result**: ❌ FAILED - Doesn't affect central-publishing plugin injection

### Attempt 5: Plugin Override with Disable
**Commit**: `4c6ceac92` - "Override central-publishing plugin with explicit disable"
**Change**: Added explicit plugin configuration with multiple skip flags
**Result**: ❌ FAILED - Injection mechanism bypasses explicit configuration

### Attempt 6: GitHub Actions Server ID Change
**Commit**: `70c9429be` - "Fix GitHub Actions setup-java to use server-id: github"
**Change**: Changed `setup-java` action to use `server-id: github`
**Result**: ❌ FAILED - Central plugin still looks for server id "central"

## Fix Approaches to Try

### Option 1: Complete Extension Exclusion (HIGH PRIORITY)
**Strategy**: Use Maven extension exclusion mechanism to prevent auto-activation

**Implementation**:
```xml
<!-- .mvn/extensions.xml -->
<extensions>
  <extension>
    <groupId>org.sonatype.central</groupId>
    <artifactId>central-publishing-maven-plugin</artifactId>
    <disabled>true</disabled>
  </extension>
</extensions>
```

**Files**: 1 (`.mvn/extensions.xml`)
**Estimated Effort**: 1 hour (research + test)
**Risk**: Low - isolated configuration file
**Likelihood of Success**: Medium (extension mechanism may not support `disabled`)

### Option 2: Maven Build Lifecycle Customization
**Strategy**: Create custom build lifecycle that excludes central-publishing phase

**Implementation**: Custom Maven lifecycle in `.mvn/maven.xml`

**Files**: 2 (`.mvn/maven.xml`, `pom.xml`)
**Estimated Effort**: 2 hours (complex Maven internals)
**Risk**: Medium - may affect other build phases
**Likelihood of Success**: Medium-High

### Option 3: Alternative Publisher - JReleaser
**Strategy**: Replace Maven deploy with JReleaser for GitHub Packages publishing

**Implementation**:
- Remove `distributionManagement` from pom.xml
- Add `jreleaser-maven-plugin` configuration
- Use `jreleaser:deploy` instead of `mvn deploy`

**Files**: 3 (`pom.xml`, `publish-snapshot.yml`, `publish-release.yml`)
**Estimated Effort**: 3 hours (new tool, different approach)
**Risk**: Medium - changes deployment strategy
**Likelihood of Success**: High (JReleaser designed for this)
**Note**: Previous commits show JReleaser was attempted (commits `ab3e488d2`, `bcf9e2324`, `f5ca521b9`)

### Option 4: Direct GitHub Packages API Upload
**Strategy**: Build artifacts with `mvn package`, upload directly via GitHub REST API

**Implementation**:
```bash
mvn -B package -DskipTests
curl -X PUT \
  -H "Authorization: token ${GITHUB_TOKEN}" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @target/flyway-core-11.20.0.jar \
  https://maven.pkg.github.com/...
```

**Files**: 2 (workflow files)
**Estimated Effort**: 2 hours (scripting + testing)
**Risk**: Low - workflow-only change
**Likelihood of Success**: High (direct API call)

### Option 5: Fork-Specific Build Profile
**Strategy**: Create Maven profile that completely isolates GitHub Packages deployment

**Implementation**:
```xml
<profiles>
  <profile>
    <id>github-packages</id>
    <build>
      <plugins>
        <!-- Only plugins needed for GitHub Packages -->
        <!-- Explicitly exclude central-publishing -->
      </plugins>
    </build>
  </profile>
</profiles>
```

**Command**: `mvn -B deploy -P github-packages`

**Files**: 2 (`pom.xml`, workflow files)
**Estimated Effort**: 2 hours
**Risk**: Low - additive change
**Likelihood of Success**: Medium

## Verification Strategy

Once a fix is implemented, verify with:

1. **Local Test**:
   ```bash
   mvn -B deploy -DskipTests -s ~/.m2/settings.xml
   ```
   Expect: No central-publishing plugin execution visible in logs

2. **GitHub Actions Test**:
   - Push to feature branch
   - Watch workflow run
   - Verify "publish" step succeeds
   - Check GitHub Packages for published artifact

3. **Success Criteria**:
   - No mention of `central-publishing-maven-plugin` in logs
   - No lookup for server id `central`
   - Artifacts appear in GitHub Packages UI
   - Workflow status: Success (green checkmark)

## Impact Assessment

**Blocking**:
- Story 3: GitHub Packages Publishing (Task 3.1, 3.2, 3.3)
- Story 4: Application Integration (all tasks)

**Workaround Available**: Yes - local Maven installation (`mvn clean install`)

**Urgency**: Critical for CI/CD and multi-developer usage, Medium for single developer with workaround

**Effort to Workaround**: 5 minutes per developer per rebuild

## Related Issues

**Upstream Maven Issues**:
- Maven Central Publishing Plugin aggressive auto-injection behavior
- Lack of documented opt-out mechanism for auto-injected plugins
- Extension exclusion mechanism not well-documented

**Project History**:
- Multiple commits show iterative attempts to fix (6+ commits)
- ~6 hours spent attempting resolution
- 10+ failed workflow runs

## Next Steps

1. **Research Maven Extension Exclusion** (30 minutes)
   - Read Maven extension documentation
   - Check if `disabled` attribute exists
   - Look for precedent in other projects

2. **Try Option 1: Extension Exclusion** (1 hour)
   - Implement `.mvn/extensions.xml` changes
   - Test locally
   - Push and verify workflow

3. **If Option 1 Fails, Try Option 4: Direct API** (2 hours)
   - Fastest alternative with high success likelihood
   - Low risk (workflow-only change)
   - Clear path to success

4. **Document Solution** (30 minutes)
   - Update this bug documentation with working solution
   - Update TODO.md and task documentation
   - Create knowledge base entry for future reference

## Technical Context

**Maven Version**: 3.8.8 (downgraded from 3.9+)
**Java Version**: 17
**Build Tool**: Maven
**Target**: GitHub Packages (maven.pkg.github.com)
**CI Platform**: GitHub Actions
**Plugin Source**: Auto-injected by Maven build lifecycle

**Maven Dependency Tree** (deploy phase):
```
deploy phase
├─ maven-deploy-plugin:deploy (explicit, configured)
│  └─ Target: https://maven.pkg.github.com/...
└─ central-publishing-maven-plugin:publish (INJECTED)
   └─ Target: Maven Central (blocks build)
```

## Success Criteria for Resolution

- ✅ GitHub Actions workflow completes without error
- ✅ No `central-publishing-maven-plugin` execution in logs
- ✅ Artifacts published to `https://maven.pkg.github.com/TylerStaplerAtFanatics/flyway`
- ✅ Artifacts downloadable with GitHub token authentication
- ✅ No Maven Central publishing attempted or configured
- ✅ Solution documented and reproducible
