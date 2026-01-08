# GitHub Packages Setup for Flyway Fork

**Date**: 2026-01-07
**Purpose**: Publish and consume Flyway fork artifacts from GitHub Packages

## Overview

The Flyway fork is configured to publish to GitHub Packages, making it easy to use the fixed version in your projects without manual installation to local Maven repository.

## GitHub Actions Workflows

### 1. Publish Release (`publish-release.yml`)

**Triggers**:
- When a GitHub release is published
- Manual dispatch with version input

**Modules Published**:
- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-database-postgresql`
- `org.flywaydb:flyway-gradle-plugin`
- `org.flywaydb:flyway-maven-plugin`

**Permissions Required**:
- `contents: read` - Read repository
- `packages: write` - Publish to GitHub Packages

### 2. Publish Snapshot (`publish-snapshot.yml`)

**Triggers**:
- Push to `main` branch
- Push to any `tylerstapler/**` branch
- Manual dispatch

**Purpose**: Provides automatic snapshot builds for testing changes before release

## How to Publish a Release

### Option 1: GitHub Release (Recommended)

1. Create a Git tag:
```bash
git tag -a v11.20.0-fork.1 -m "Fix configuration prefix bug"
git push origin v11.20.0-fork.1
```

2. Create a GitHub Release from the tag:
   - Go to GitHub repository → Releases → Draft a new release
   - Select the tag `v11.20.0-fork.1`
   - Write release notes
   - Click "Publish release"

3. GitHub Actions will automatically build and publish to GitHub Packages

### Option 2: Manual Workflow Dispatch

1. Go to GitHub Actions → "Publish Release to GitHub Packages"
2. Click "Run workflow"
3. Enter the version (e.g., `11.20.0`)
4. Click "Run workflow"

### Option 3: Local Publish

```bash
# Set environment variable for GitHub repository
export GITHUB_REPOSITORY="owner/repo"

# Build and deploy
mvn -B clean deploy \
  -DskipTests \
  -pl flyway-core,flyway-database/flyway-database-postgresql,flyway-plugins/flyway-gradle-plugin,flyway-plugins/flyway-maven-plugin \
  -s ~/.m2/settings.xml
```

Requires `~/.m2/settings.xml` with GitHub token:
```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>GITHUB_USERNAME</username>
      <password>GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

## How to Consume the Published Fork

### Maven Configuration

Add to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/OWNER/REPO</url>
    <snapshots>
      <enabled>true</enabled>
    </snapshots>
  </repository>
</repositories>

<dependencies>
  <!-- Flyway Core -->
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <version>11.20.0</version>
  </dependency>

  <!-- PostgreSQL Database Support -->
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
    <version>11.20.0</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <!-- Flyway Maven Plugin -->
    <plugin>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-maven-plugin</artifactId>
      <version>11.20.0</version>
      <configuration>
        <url>jdbc:postgresql://localhost:5432/mydb</url>
        <user>postgres</user>
        <password>password</password>
        <pluginConfiguration>
          <postgresqlTransactionalLock>false</postgresqlTransactionalLock>
        </pluginConfiguration>
      </configuration>
    </plugin>
  </plugins>
</build>
```

### Maven Settings Authentication

Create or update `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_PERSONAL_ACCESS_TOKEN</password>
    </server>
  </servers>
</settings>
```

**GitHub Token Permissions Required**:
- `read:packages` - Download packages

**To create a token**:
1. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Generate new token (classic)
3. Select scopes: `read:packages` (for downloading), `write:packages` (for publishing)
4. Copy the token and use it as password in settings.xml

### Gradle Configuration

Add to your `build.gradle`:

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/OWNER/REPO")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation 'org.flywaydb:flyway-core:11.20.0'
    implementation 'org.flywaydb:flyway-database-postgresql:11.20.0'
}

flyway {
    url = 'jdbc:postgresql://localhost:5432/mydb'
    user = 'postgres'
    password = 'password'
    pluginConfiguration = [
        postgresqlTransactionalLock: 'false'
    ]
}
```

Add to `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```

## Versioning Strategy

### Release Versions
- **Format**: `11.20.0-fork.X` (where X is the fork iteration)
- **Example**: `11.20.0-fork.1`, `11.20.0-fork.2`
- **Use Case**: Stable releases with the configuration prefix fix

### Snapshot Versions
- **Format**: `11.20.0-SNAPSHOT`
- **Use Case**: Testing unreleased changes
- **Note**: Snapshots are rebuilt on every push to main or feature branches

## Workflow Permissions

The GitHub Actions workflows require the following repository permissions:

**Settings → Actions → General → Workflow permissions**:
- ✅ Read and write permissions
- ✅ Allow GitHub Actions to create and approve pull requests (if needed)

## Troubleshooting

### Error: 401 Unauthorized

**Problem**: Can't download packages from GitHub Packages

**Solution**:
1. Verify GitHub token has `read:packages` scope
2. Check `~/.m2/settings.xml` has correct username and token
3. Ensure repository visibility allows package access

### Error: 403 Forbidden

**Problem**: Can't publish to GitHub Packages

**Solution**:
1. Verify GitHub token has `write:packages` scope
2. Check workflow has `packages: write` permission
3. Verify you have admin/write access to the repository

### Error: Package already exists

**Problem**: Trying to publish a version that already exists

**Solution**:
1. Delete the existing package version (if needed)
2. Increment the version number
3. Use `-SNAPSHOT` suffix for development versions

### Testing Without GitHub Packages

For local development without GitHub Packages:

```bash
# Install to local Maven repository
mvn clean install -DskipTests -pl flyway-core,flyway-database/flyway-database-postgresql,flyway-plugins/flyway-gradle-plugin,flyway-plugins/flyway-maven-plugin -am

# Use in projects without adding GitHub repository
# Dependencies will be resolved from ~/.m2/repository/
```

## CI/CD Integration

### GitHub Actions Example

```yaml
- name: Authenticate with GitHub Packages
  uses: actions/setup-java@v4
  with:
    java-version: 17
    distribution: 'temurin'
    server-id: github
    server-username: GITHUB_ACTOR
    server-password: GITHUB_TOKEN

- name: Build with Flyway Fork
  run: mvn clean install
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

### Jenkins Example

```groovy
withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
    sh '''
        echo "<server><id>github</id><username>\${GITHUB_ACTOR}</username><password>\${GITHUB_TOKEN}</password></server>" > ~/.m2/settings.xml
        mvn clean install
    '''
}
```

## Files Modified

### Workflows Created
- `.github/workflows/publish-release.yml` - Release publishing
- `.github/workflows/publish-snapshot.yml` - Snapshot publishing

### Configuration Updated
- `pom.xml` - Added `<distributionManagement>` section

## Next Steps

1. **Test the workflow**: Push to a feature branch to trigger snapshot build
2. **Create a release**: Tag and create GitHub release to publish version
3. **Update application**: Switch from local Maven to GitHub Packages
4. **Verify in CI/CD**: Ensure builds work with GitHub Packages authentication

## References

- [GitHub Packages Documentation](https://docs.github.com/en/packages)
- [Maven Deploy Plugin](https://maven.apache.org/plugins/maven-deploy-plugin/)
- [GitHub Actions - Publishing Java packages](https://docs.github.com/en/actions/publishing-packages/publishing-java-packages-with-maven)
