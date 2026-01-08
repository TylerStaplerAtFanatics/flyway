# Research Checklist

## Phase 1: Configuration Pipeline Analysis
- [ ] **Task 1.1**: Read ConfigUtils.java completely to understand configuration system
  - Agent: codebase-analyzer
  - Output: `research/01-config-utils-analysis.md`
  - Prompt: ```
    Read your instructions from `codebase-analyzer`.
    Analyze the file flyway-core/src/main/java/org/flywaydb/core/internal/configuration/ConfigUtils.java
    Focus on:
    - How FLYWAY_PLUGINS_PREFIX is defined and used
    - How configuration properties are expected to be formatted
    - Any validation or transformation logic for plugin configurations
    - How properties flow from plugins (Maven/Gradle) to core
    Save your results into research/01-config-utils-analysis.md
    ```

- [ ] **Task 1.2**: Find where plugin configurations are consumed in Flyway core
  - Agent: codebase-locator
  - Output: `research/02-plugin-config-consumers.md`
  - Prompt: ```
    Read your instructions from `codebase-locator`.
    Find all locations where configuration keys starting with "flyway.plugins." are read or consumed.
    Search for:
    - References to FLYWAY_PLUGINS_PREFIX constant
    - Code that reads properties matching "flyway.plugins.*" pattern
    - Plugin registration or initialization code
    Save your results into research/02-plugin-config-consumers.md
    ```

## Phase 2: Test Coverage Analysis
- [ ] **Task 2.1**: Find existing tests for Gradle plugin configuration
  - Agent: codebase-locator
  - Output: `research/03-gradle-plugin-tests.md`
  - Prompt: ```
    Read your instructions from `codebase-locator`.
    Find test files related to the Gradle plugin, specifically:
    - Tests for AbstractFlywayTask
    - Tests for FlywayExtension
    - Tests for plugin configuration handling
    - Tests for getPluginConfiguration method
    Look in flyway-plugins/flyway-gradle-plugin/src/test/ directory
    Save your results into research/03-gradle-plugin-tests.md
    ```

- [ ] **Task 2.2**: Find existing tests for Maven plugin configuration
  - Agent: codebase-locator
  - Output: `research/04-maven-plugin-tests.md`
  - Prompt: ```
    Read your instructions from `codebase-locator`.
    Find test files related to the Maven plugin, specifically:
    - Tests for AbstractFlywayMojo
    - Tests for plugin configuration handling
    Look in flyway-plugins/flyway-maven-plugin/src/test/ directory
    Save your results into research/04-maven-plugin-tests.md
    ```

## Phase 3: Maven Plugin Comparison
- [ ] **Task 3.1**: Analyze Maven plugin's equivalent method
  - Agent: codebase-analyzer
  - Output: `research/05-maven-plugin-analysis.md`
  - Prompt: ```
    Read your instructions from `codebase-analyzer`.
    Analyze the file flyway-plugins/flyway-maven-plugin/src/main/java/org/flywaydb/maven/AbstractFlywayMojo.java
    Focus on the method that handles plugin configuration (similar to getPluginConfiguration in Gradle plugin).
    Compare with the Gradle implementation and document:
    - Any differences in implementation
    - Whether it has the same potential bug
    - How it transforms configuration keys
    Save your results into research/05-maven-plugin-analysis.md
    ```

## Phase 4: Pattern Analysis
- [ ] **Task 4.1**: Find examples of plugin configuration usage in codebase
  - Agent: codebase-pattern-finder
  - Output: `research/06-plugin-config-patterns.md`
  - Prompt: ```
    Read your instructions from `codebase-pattern-finder`.
    Find examples in the codebase showing:
    - How pluginConfiguration is set/defined in build files or tests
    - What format plugin configuration keys are expected to have
    - Any documentation about plugin configuration format
    Search for patterns like:
    - "pluginConfiguration"
    - "flyway.plugins."
    - Configuration examples in test resources or documentation
    Save your results into research/06-plugin-config-patterns.md
    ```

## Phase 5: Bug Reports and Documentation
- [ ] **Task 5.1**: Search for related bug reports or issues
  - Agent: codebase-locator
  - Output: `research/07-bug-reports.md`
  - Prompt: ```
    Read your instructions from `codebase-locator`.
    Search for references to this bug in:
    - Release notes
    - Changelog files
    - Issue templates
    - Comments in code mentioning "prefix" or "plugin config"
    - Git commit messages mentioning this branch or related fixes
    Save your results into research/07-bug-reports.md
    ```

## Phase 6: Synthesis
- [ ] **Task 6.1**: Manual analysis - Review all findings and document the bug
- [ ] **Task 6.2**: Create comprehensive research summary with examples
- [ ] **Task 6.3**: Propose potential fixes with pros/cons
