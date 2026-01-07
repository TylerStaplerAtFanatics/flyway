/*-
 * ========================LICENSE_START=================================
 * flyway-gradle-plugin
 * ========================================================================
 * Copyright (C) 2010 - 2025 Red Gate Software Ltd
 * ========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.flywaydb.gradle.task;

import org.flywaydb.core.Flyway;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for AbstractFlywayTask plugin configuration handling.
 */
public class AbstractFlywayTaskTest {

    private TestFlywayTask task;

    @Before
    public void setUp() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("org.flywaydb.flyway");
        task = project.getTasks().create("testTask", TestFlywayTask.class);
    }

    @Test
    public void pluginConfiguration_postgresqlTransactionalLock_shouldUseFlywayPrefix() {
        // Given
        Map<String, String> pluginConfig = new HashMap<>();
        pluginConfig.put("postgresqlTransactionalLock", "false");

        // When
        Map<String, String> result = task.getPluginConfiguration(pluginConfig, null);

        // Then
        assertEquals("false", result.get("flyway.postgresql.transactional.lock"));
        assertNull("Should not use plugins prefix", result.get("flyway.plugins.postgresql.transactional.lock"));
    }

    @Test
    public void pluginConfiguration_mysqlSetting_shouldUseFlywayPrefix() {
        // Given
        Map<String, String> pluginConfig = new HashMap<>();
        pluginConfig.put("mysqlSomeSetting", "value");

        // When
        Map<String, String> result = task.getPluginConfiguration(pluginConfig, null);

        // Then
        assertEquals("value", result.get("flyway.mysql.some.setting"));
        assertNull("Should not use plugins prefix", result.get("flyway.plugins.mysql.some.setting"));
    }

    @Test
    public void pluginConfiguration_oracleSqlplus_shouldUseFlywayPrefix() {
        // Given
        Map<String, String> pluginConfig = new HashMap<>();
        pluginConfig.put("oracleSqlplus", "true");

        // When
        Map<String, String> result = task.getPluginConfiguration(pluginConfig, null);

        // Then
        assertEquals("true", result.get("flyway.oracle.sqlplus"));
        assertNull("Should not use plugins prefix", result.get("flyway.plugins.oracle.sqlplus"));
    }

    @Test
    public void pluginConfiguration_actualPlugin_shouldUsePluginsPrefix() {
        // Given
        Map<String, String> pluginConfig = new HashMap<>();
        pluginConfig.put("customPluginSetting", "value");

        // When
        Map<String, String> result = task.getPluginConfiguration(pluginConfig, null);

        // Then
        assertEquals("value", result.get("flyway.plugins.custom.plugin.setting"));
        assertNull("Should not use direct flyway prefix", result.get("flyway.custom.plugin.setting"));
    }

    @Test
    public void pluginConfiguration_camelCaseConversion_shouldConvertCorrectly() {
        // Given
        Map<String, String> pluginConfig = new HashMap<>();
        pluginConfig.put("postgresqlTransactionalLock", "false");

        // When
        Map<String, String> result = task.getPluginConfiguration(pluginConfig, null);

        // Then
        assertTrue("Should contain converted property", result.containsKey("flyway.postgresql.transactional.lock"));
    }

    @Test
    public void pluginConfiguration_multipleSettings_shouldHandleCorrectly() {
        // Given
        Map<String, String> pluginConfig = new HashMap<>();
        pluginConfig.put("postgresqlTransactionalLock", "false");
        pluginConfig.put("mysqlOption", "value1");
        pluginConfig.put("customPluginSetting", "value2");

        // When
        Map<String, String> result = task.getPluginConfiguration(pluginConfig, null);

        // Then
        assertEquals("false", result.get("flyway.postgresql.transactional.lock"));
        assertEquals("value1", result.get("flyway.mysql.option"));
        assertEquals("value2", result.get("flyway.plugins.custom.plugin.setting"));
    }

    @Test
    public void pluginConfiguration_extensionPluginConfiguration_shouldWorkSameAsPluginConfiguration() {
        // Given
        Map<String, String> extensionConfig = new HashMap<>();
        extensionConfig.put("postgresqlTransactionalLock", "false");

        // When
        Map<String, String> result = task.getPluginConfiguration(null, extensionConfig);

        // Then
        assertEquals("false", result.get("flyway.postgresql.transactional.lock"));
    }

    @Test
    public void pluginConfiguration_bothConfigurationTypes_shouldMerge() {
        // Given
        Map<String, String> pluginConfig = new HashMap<>();
        pluginConfig.put("postgresqlTransactionalLock", "false");

        Map<String, String> extensionConfig = new HashMap<>();
        extensionConfig.put("mysqlOption", "value");

        // When
        Map<String, String> result = task.getPluginConfiguration(pluginConfig, extensionConfig);

        // Then
        assertEquals("false", result.get("flyway.postgresql.transactional.lock"));
        assertEquals("value", result.get("flyway.mysql.option"));
    }

    @Test
    public void pluginConfiguration_nullConfigurations_shouldReturnEmpty() {
        // When
        Map<String, String> result = task.getPluginConfiguration(null, null);

        // Then
        assertTrue("Should return empty map", result.isEmpty());
    }

    @Test
    public void pluginConfiguration_allDatabasePrefixes_shouldUseFlywayPrefix() {
        // Given - test a representative sample of database prefixes
        Map<String, String> pluginConfig = new HashMap<>();
        pluginConfig.put("postgresqlSetting", "val1");
        pluginConfig.put("mysqlSetting", "val2");
        pluginConfig.put("oracleSetting", "val3");
        pluginConfig.put("sqlserverSetting", "val4");
        pluginConfig.put("h2Setting", "val5");
        pluginConfig.put("snowflakeSetting", "val6");

        // When
        Map<String, String> result = task.getPluginConfiguration(pluginConfig, null);

        // Then - all should use flyway. prefix, not flyway.plugins.
        assertTrue(result.containsKey("flyway.postgresql.setting"));
        assertTrue(result.containsKey("flyway.mysql.setting"));
        assertTrue(result.containsKey("flyway.oracle.setting"));
        assertTrue(result.containsKey("flyway.sqlserver.setting"));
        assertTrue(result.containsKey("flyway.h2.setting"));
        assertTrue(result.containsKey("flyway.snowflake.setting"));

        assertFalse(result.containsKey("flyway.plugins.postgresql.setting"));
        assertFalse(result.containsKey("flyway.plugins.mysql.setting"));
    }

    /**
     * Test implementation of AbstractFlywayTask that exposes getPluginConfiguration for testing.
     */
    public static class TestFlywayTask extends AbstractFlywayTask {
        @Override
        protected Object run(Flyway flyway) {
            return null;
        }

        // Expose for testing
        @Override
        public Map<String, String> getPluginConfiguration(Map<String, String> pluginConfiguration,
                                                          Map<String, String> extensionPluginConfiguration) {
            return super.getPluginConfiguration(pluginConfiguration, extensionPluginConfiguration);
        }
    }
}
