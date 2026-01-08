# GitHub Packages Deployment Troubleshooting

**Date**: 2026-01-08
**Issue**: Cannot deploy Flyway fork artifacts to GitHub Packages due to central-publishing-maven-plugin interference

## Problem Summary

When trying to publish Flyway fork to GitHub Packages, the `central-publishing-maven-plugin` (version 0.7.0) auto-injects itself into the Maven deploy lifecycle and fails with:

```
Failed to execute goal org.sonatype.central:central-publishing-maven-plugin:0.7.0:publish (injected-central-publishing) on project flyway-core:
Execution injected-central-publishing of goal org.sonatype.central:central-publishing-maven-plugin:0.7.0:publish failed:
Unable to get publisher server properties for server id: central:
Cannot invoke "org.apache.maven.settings.Server.clone()" because "server" is null
```

This plugin is designed for publishing to Maven Central and expects a `central` server configuration in settings.xml, which we don't have (and don't want) for GitHub Packages deployment.

## Attempts to Fix

### Attempt 1: Use System Property to Skip
**Approach**: Add `-Dcentral.publishing.skip=true` to Maven command
**Commit**: 2de7eeef8
**Result**: ❌ Failed - Property not recognized by plugin
**Why it failed**: Plugin version 0.7.0 doesn't support this property

---

### Attempt 2: Configure Skip in Plugin
**Approach**: Add `<skip>${skipCentralPublishing}</skip>` to plugin configuration with `-DskipCentralPublishing=true`
**Commit**: c234916e1
**Result**: ❌ Failed - Plugin still executing
**Why it failed**: Configuration not read when plugin is auto-injected

---

### Attempt 3: Comment Out Plugin Block
**Approach**: Comment out entire plugin definition in pom.xml
**Commit**: 6f95bc9cb
**Result**: ❌ Failed - Maven still loading plugin
**Why it failed**: Plugin loads from somewhere external (parent POM or Maven extension mechanism)

---

### Attempt 4: Remove Plugin Block Entirely
**Approach**: Delete plugin block from pom.xml
**Commit**: e064ceeec
**Result**: ❌ Failed - Plugin still auto-injecting
**Why it failed**: Plugin has `<extensions>true</extensions>` somewhere in POM hierarchy, causing auto-injection

---

### Attempt 5: Set Execution Phase to None
**Approach**: Use `<phase>none</phase>` for `injected-central-publishing` execution ID in pluginManagement
**Commit**: ccfd3b308
**Result**: ❌ Failed - Still using version 0.7.0 despite specifying 0.9.0
**Why it failed**: Maven caching old plugin version or parent POM override

---

### Attempt 6: Upgrade to 0.9.0 with skipPublishing
**Approach**: Upgrade plugin to 0.9.0 (where `skipPublishing` was fixed) and set `skipPublishing=true`
**Commits**: 167e504d0, 84ccb24bc
**Result**: ❌ Failed - Logs still showed version 0.7.0 being used
**Why it failed**: Maven plugin resolution not picking up newer version from pluginManagement

---

### Attempt 7: Add to PluginManagement with Phase None
**Approach**: Added plugin to pluginManagement with both execution IDs set to phase=none
**Commit**: 9eb7a3f70
**Result**: ❌ Failed - Still using version 0.7.0
**Why it failed**: PluginManagement doesn't override auto-injected plugin behavior

---

### Attempt 8: Use package + deploy:deploy Instead of Full Deploy Lifecycle
**Approach**: Changed workflow from `mvn install` + `mvn deploy` to `mvn package` + `mvn deploy:deploy`
**Commit**: 5048659af
**Workflow Run**: 20805886986
**Result**: ✅ Plugin no longer runs! But ❌ New error: "repository element was not specified in the POM inside distributionManagement element"
**Why it partially worked**: `deploy:deploy` goal bypasses lifecycle where plugin injects
**Why it failed**: Direct goal invocation requires repository to be explicitly specified

---

### Attempt 9: Add Concrete Repository URL to distributionManagement
**Approach**: Changed `${env.GITHUB_REPOSITORY}` to concrete path `https://maven.pkg.github.com/TylerStaplerAtFanatics/flyway`
**Commit**: 9dda79e5e
**Result**: ❌ Still same error about repository not specified
**Why it failed**: Maven doesn't read distributionManagement when using `deploy:deploy` directly with `-pl` option

---

### Attempt 10: Add -DaltDeploymentRepository Parameter
**Approach**: Specify repository via `-DaltDeploymentRepository=github::default::https://maven.pkg.github.com/${{ github.repository }}`
**Commit**: 37cad9402
**Result**: ✅ Repository parameter recognized! But ❌ New error: "NoFileAssignedException: The packaging for this project did not assign a file to the build artifact"
**Why it partially worked**: Repository configuration now correct
**Why it failed**: JAR files not attached because `package` phase doesn't make artifacts available for `deploy:deploy`

---

### Attempt 11: Build All Modules (No -pl flag)
**Approach**: Remove `-pl` flag to build all modules, hoping parent POM inheritance works better
**Commit**: 37cad9402
**Result**: ❌ Failed - MongoDB module requires auth to flyway-community-db-support repository (401 Unauthorized)
**Why it failed**: Building all modules includes flyway-database-mongodb which needs external GitHub Packages auth

---

### Attempt 12: Remove MongoDB Modules
**Approach**: Comment out MongoDB modules from flyway-database/pom.xml
**Commit**: db7430214
**Result**: ❌ Still failed with central-publishing plugin running
**Why it failed**: Went back to using full `deploy` lifecycle which triggered the plugin

---

### Attempt 13: Use install + deploy:deploy
**Approach**: Run `mvn install` to build and attach artifacts, then `mvn deploy:deploy` with altDeploymentRepository
**Commit**: af7437d39
**Result**: ❌ Failed - "NoFileAssignedException" on flyway-database-postgresql
**Why it failed**: `install` doesn't make artifacts available for subsequent `deploy:deploy` goal invocation

---

### Attempt 14: Empty extensions.xml to Prevent Auto-Discovery
**Approach**: Create `.mvn/extensions.xml` with empty `<extensions>` list
**Commit**: 6bbbf0324
**Result**: ❌ Failed - Plugin still auto-loading
**Why it failed**: Maven extension mechanism doesn't respect empty extensions.xml for disabling

---

### Attempt 15: Explicitly Configure Plugin with Skip in Plugins Section
**Approach**: Add plugin to `<plugins>` (not just pluginManagement) with `<skip>true</skip>`, `<skipPublishing>true</skipPublishing>`, and execution phase=none
**Commit**: 9e7753a08
**Workflow Run**: 20806369929
**Result**: ❌ Failed - Plugin STILL running
**Why it failed**: Plugin's auto-injection mechanism overrides explicit configuration

---

## Root Cause Analysis

The `central-publishing-maven-plugin` uses Maven's extension mechanism with `<extensions>true</extensions>`, which gives it special privileges:

1. **Auto-Injection**: Plugin automatically injects itself into the deploy lifecycle phase
2. **Execution ID**: Uses `injected-central-publishing` as execution ID
3. **Configuration Override**: Explicit configuration in pom.xml is ignored
4. **Version Pinning**: Maven caches version 0.7.0 somewhere in POM hierarchy

The plugin expects a Maven Central deployment setup:
- Server ID `central` in settings.xml
- Credentials for Maven Central
- This conflicts with GitHub Packages deployment which uses server ID `github`

## Current State

**Files Modified**:
- `pom.xml`: Version updated to 11.20.0-fork.1, added distributionManagement, attempted various plugin configurations
- `flyway-database/pom.xml`: Removed MongoDB modules to avoid auth issues
- `.mvn/extensions.xml`: Created empty extensions file (didn't help)
- `.github/workflows/publish-snapshot.yml`: Multiple iterations of build/deploy commands
- `.github/workflows/publish-release.yml`: Multiple iterations of build/deploy commands

**Current Workflow**:
```yaml
- name: Publish to GitHub Packages
  run: mvn -B deploy -DskipTests -pl flyway-core,flyway-database/flyway-database-postgresql,flyway-plugins/flyway-gradle-plugin,flyway-plugins/flyway-maven-plugin -am
```

**Current POM Plugin Configuration**:
```xml
<plugin>
  <groupId>org.sonatype.central</groupId>
  <artifactId>central-publishing-maven-plugin</artifactId>
  <version>0.7.0</version>
  <configuration>
    <skip>true</skip>
    <skipPublishing>true</skipPublishing>
  </configuration>
  <executions>
    <execution>
      <id>injected-central-publishing</id>
      <phase>none</phase>
      <goals>
        <goal>publish</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

---

### Attempt 21: Switch to JReleaser Maven Plugin
**Approach**: Replace Maven deploy with JReleaser Maven Plugin (jreleaser:deploy) which is specifically designed for multi-platform publishing
**Documentation**: https://jreleaser.org/guide/latest/tools/jreleaser-maven.html
**Changes Made**:
- Added jreleaser-maven-plugin version 1.15.0 to pom.xml with GitHub Packages configuration
- Split workflow into two steps: `mvn install` to build artifacts, then `mvn jreleaser:deploy` to publish
- Set environment variables: JRELEASER_GITHUB_TOKEN, JRELEASER_GPG_* (empty for no signing)
- Kept distributionManagement section for compatibility
**Result**: ❌ Failed - JReleaser requires stagingDirectories configuration and is designed for GitHub Releases, not Maven package repos
**Why it failed**: JReleaser is primarily for GitHub Releases. GitHub Packages Maven deployment needs standard Maven deploy process

---

### Attempt 22: Use Maven 3.8.8 to Avoid Auto-Injection
**Approach**: Downgrade from Maven 3.9.11 to Maven 3.8.8 which doesn't have the central-publishing plugin auto-injection feature
**Key Discovery**: Maven 3.9.x logs show "Installing Central Publishing features" - this is a built-in feature in 3.9.x that auto-injects the plugin
**Changes Made**:
- Added step to download and install Maven 3.8.8 from Apache archives
- Removed cache: 'maven' from setup-java (not needed when installing custom Maven)
- Added verification step to confirm Maven version
- Keep using standard `mvn deploy` command
**Rationale**: Maven 3.8.x doesn't have the auto-injection feature, so it should work like the fanatics-gaming examples
**Status**: Testing

---

## Potential Solutions Not Yet Tried

### Option 1: Find and Remove Parent POM Extension
- Search entire POM hierarchy for where `<extensions>true</extensions>` is defined
- Remove or override in parent POM chain
- May require forking upstream parent POMs

### Option 2: Use Maven Assembly Plugin
- Build artifacts with assembly plugin
- Upload directly to GitHub Packages using GitHub API or gh CLI
- Bypass Maven deployment mechanism entirely

### Option 3: Custom Deployment Script
- Use `mvn package` to build JARs
- Write shell script to upload to GitHub Packages via REST API
- Would need to handle POM, JAR, sources JAR uploads separately

### Option 4: Maven Wagon GitHub
- Use `wagon-github` extension for GitHub Packages
- Configure as transport mechanism instead of using maven-deploy-plugin
- May still conflict with central-publishing plugin

### Option 5: Disable All Extensions
- Add `-Dmaven.ext.class.path=` to Maven command to disable all extensions
- Nuclear option that might break other things
- Would need testing

### Option 6: Fork Flyway Parent POM
- Create our own parent POM without central-publishing configuration
- Point child modules to new parent
- Most invasive but most reliable solution

### Option 7: Use Nexus Repository Manager
- Set up own Nexus instance
- Deploy there instead of GitHub Packages
- Adds infrastructure complexity

## Recommended Next Steps

1. **Investigate Parent POM Chain**:
   ```bash
   mvn help:effective-pom > effective-pom.xml
   grep -A 20 "central-publishing" effective-pom.xml
   ```
   Find where the plugin is actually being configured with extensions

2. **Try -Dmaven.ext.class.path override**:
   ```bash
   mvn -Dmaven.ext.class.path= deploy -DskipTests
   ```
   See if disabling all extensions helps

3. **Consider Manual Upload**:
   Use GitHub API to upload artifacts directly, bypassing Maven deployment entirely

## References

- Stack Overflow: https://stackoverflow.com/questions/79748938/how-to-skip-central-publishing-maven-plugin-from-mvn-commandline
- Sonatype Documentation: https://central.sonatype.org/publish/publish-portal-maven/
- GitHub Maven Registry: https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry
