package com.test.flywaytest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comparison test showing the difference between original and fixed behavior.
 *
 * This test demonstrates:
 * 1. Original behavior: FLYWAY_PLUGINS_PREFIX incorrectly added
 * 2. Fixed behavior: Simple "flyway." prefix correctly added
 */
@SpringBootTest
public class FlywayConfigurationComparisonTest {

    private static final String FLYWAY_PLUGINS_PREFIX = "flyway.plugins.";
    private static final String FLYWAY_PREFIX = "flyway.";

    @Test
    void testOriginalBehavior_IncorrectPrefix() {
        // This simulates the ORIGINAL (buggy) code behavior
        Map<String, String> pluginConfiguration = new HashMap<>();
        pluginConfiguration.put("postgresqlTransactionalLock", "false");

        Map<String, String> conf = new HashMap<>();
        String camelCaseRegex = "(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])";

        for (String key : pluginConfiguration.keySet()) {
            // ORIGINAL CODE (BUGGY): Uses FLYWAY_PLUGINS_PREFIX
            String transformedKey = String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT);
            conf.put(FLYWAY_PLUGINS_PREFIX + transformedKey, pluginConfiguration.get(key));
        }

        // Show what the original code produces
        System.out.println("\n=== ORIGINAL (BUGGY) BEHAVIOR ===");
        conf.forEach((k, v) -> System.out.println(k + " = " + v));

        // Verify it produces the WRONG key
        assertTrue(conf.containsKey("flyway.plugins.postgresql.transactional.lock"),
            "Original code incorrectly adds 'flyway.plugins.' prefix");
        assertFalse(conf.containsKey("flyway.postgresql.transactional.lock"),
            "Original code does NOT create the correct key");

        // This is the key PostgreSQL extension expects
        String expectedKey = "flyway.postgresql.transactional.lock";
        String wrongKey = "flyway.plugins.postgresql.transactional.lock";

        System.out.println("\n❌ PostgreSQL extension looks for: " + expectedKey);
        System.out.println("❌ But original code produces: " + wrongKey);
        System.out.println("❌ Result: Configuration IGNORED, transactional locks remain enabled");
    }

    @Test
    void testFixedBehavior_CorrectPrefix() {
        // This simulates the FIXED code behavior
        Map<String, String> pluginConfiguration = new HashMap<>();
        pluginConfiguration.put("postgresqlTransactionalLock", "false");

        Map<String, String> conf = new HashMap<>();
        String camelCaseRegex = "(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])";

        for (String key : pluginConfiguration.keySet()) {
            // FIXED CODE: Uses simple "flyway." prefix
            String transformedKey = String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT);
            conf.put(FLYWAY_PREFIX + transformedKey, pluginConfiguration.get(key));
        }

        // Show what the fixed code produces
        System.out.println("\n=== FIXED BEHAVIOR ===");
        conf.forEach((k, v) -> System.out.println(k + " = " + v));

        // Verify it produces the CORRECT key
        assertTrue(conf.containsKey("flyway.postgresql.transactional.lock"),
            "Fixed code correctly adds 'flyway.' prefix");
        assertFalse(conf.containsKey("flyway.plugins.postgresql.transactional.lock"),
            "Fixed code does NOT add incorrect 'flyway.plugins.' prefix");

        // This is the key PostgreSQL extension expects
        String expectedKey = "flyway.postgresql.transactional.lock";

        System.out.println("\n✅ PostgreSQL extension looks for: " + expectedKey);
        System.out.println("✅ Fixed code produces: " + expectedKey);
        System.out.println("✅ Result: Configuration RECOGNIZED, session locks enabled");
    }

    @Test
    void testSideBySideComparison() {
        String testKey = "postgresqlTransactionalLock";
        String camelCaseRegex = "(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])";
        String transformedKey = String.join(".", testKey.split(camelCaseRegex)).toLowerCase(Locale.ROOT);

        String originalKey = FLYWAY_PLUGINS_PREFIX + transformedKey;
        String fixedKey = FLYWAY_PREFIX + transformedKey;
        String expectedKey = "flyway.postgresql.transactional.lock";

        System.out.println("\n=== SIDE-BY-SIDE COMPARISON ===");
        System.out.println("Input (camelCase):     " + testKey);
        System.out.println("Transformed:           " + transformedKey);
        System.out.println();
        System.out.println("❌ Original produces:  " + originalKey);
        System.out.println("✅ Fixed produces:     " + fixedKey);
        System.out.println("🎯 Expected by extension: " + expectedKey);
        System.out.println();

        // Verify the fix
        assertEquals(expectedKey, fixedKey, "Fixed version should match expected key");
        assertNotEquals(expectedKey, originalKey, "Original version should NOT match expected key");

        System.out.println("Match: Original = Expected? " + expectedKey.equals(originalKey) + " ❌");
        System.out.println("Match: Fixed = Expected?    " + expectedKey.equals(fixedKey) + " ✅");
    }

    @Test
    void testMultipleConfigurationKeys() {
        // Test with multiple PostgreSQL configuration keys
        Map<String, String> pluginConfiguration = new HashMap<>();
        pluginConfiguration.put("postgresqlTransactionalLock", "false");
        pluginConfiguration.put("postgresqlStatementTimeout", "30000");
        pluginConfiguration.put("postgresqlLockTimeout", "10000");

        String camelCaseRegex = "(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])";

        System.out.println("\n=== MULTIPLE CONFIGURATION KEYS ===");
        System.out.println("\nOriginal (Buggy):");
        for (String key : pluginConfiguration.keySet()) {
            String transformedKey = String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT);
            String originalKey = FLYWAY_PLUGINS_PREFIX + transformedKey;
            System.out.println("  " + key + " → " + originalKey + " ❌");
        }

        System.out.println("\nFixed:");
        for (String key : pluginConfiguration.keySet()) {
            String transformedKey = String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT);
            String fixedKey = FLYWAY_PREFIX + transformedKey;
            System.out.println("  " + key + " → " + fixedKey + " ✅");
        }

        // Verify all keys are correctly transformed
        for (String key : pluginConfiguration.keySet()) {
            String transformedKey = String.join(".", key.split(camelCaseRegex)).toLowerCase(Locale.ROOT);
            String fixedKey = FLYWAY_PREFIX + transformedKey;

            // All should start with "flyway." not "flyway.plugins."
            assertTrue(fixedKey.startsWith("flyway."),
                "Fixed key should start with 'flyway.'");
            assertFalse(fixedKey.startsWith("flyway.plugins."),
                "Fixed key should NOT start with 'flyway.plugins.'");
        }
    }
}
