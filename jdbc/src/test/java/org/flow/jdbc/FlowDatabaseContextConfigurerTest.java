package org.flow.jdbc;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.EnvironmentPropertySource;
import io.micronaut.context.env.MapPropertySource;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.SystemPropertiesPropertySource;
import io.micronaut.context.env.yaml.YamlPropertySourceLoader;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDatabaseContextConfigurerTest {
    private static final String SEND_EVENT = "jooq.send-event";
    private static final String DATABASE_LISTENER = "table.db-change.listeners.pulsar.enabled";
    private static final String CONSUMERS = "pulsar.consumer.enabled";
    private static final String DEFAULT_SOURCE = "flow-jdbc-runtime-defaults";

    @Test
    void loadsDefaultsAutomaticallyThroughTheContextConfigurerSpi() {
        try (var context = isolatedBuilder().build()) {
            var environment = context.getEnvironment().start();
            assertFalse(context.isRunning(), "Loading configuration must not start database or messaging beans.");
            assertFlags(environment, false);
            var defaults = environment.getPropertySources().stream()
                .filter(source -> DEFAULT_SOURCE.equals(source.getName())).toList();
            assertEquals(1, defaults.size());
            assertEquals(Integer.MIN_VALUE, defaults.getFirst().getOrder());
        }
    }

    @Test
    void hostYamlOverridesTheRuntimeDefaultsAtTheYamlLoaderOrder() throws Exception {
        var loader = new YamlPropertySourceLoader();
        String yaml = """
            jooq:
              send-event: true
            table:
              db-change:
                listeners:
                  pulsar:
                    enabled: true
            pulsar:
              consumer:
                enabled: true
            """;
        try (var input = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))) {
            var source = PropertySource.of("host-yaml", loader.read("host-yaml", input), loader.getOrder());
            assertOverridesWithoutStartingBeans(source);
        }
    }

    @Test
    @SetSystemProperty(key = SEND_EVENT, value = "true")
    @SetSystemProperty(key = DATABASE_LISTENER, value = "true")
    @SetSystemProperty(key = CONSUMERS, value = "true")
    void actualSystemPropertiesOverrideTheRuntimeDefaults() {
        assertOverridesWithoutStartingBeans(new SystemPropertiesPropertySource());
    }

    @Test
    void environmentVariableNamesOverrideAtTheNativeEnvironmentOrder() {
        var source = new MapPropertySource("host-env", Map.of(
            "JOOQ_SEND_EVENT", "true",
            "TABLE_DB_CHANGE_LISTENERS_PULSAR_ENABLED", "true",
            "PULSAR_CONSUMER_ENABLED", "true"
        )) {
            @Override
            public int getOrder() {
                return EnvironmentPropertySource.POSITION;
            }

            @Override
            public PropertyConvention getConvention() {
                return PropertyConvention.ENVIRONMENT_VARIABLE;
            }
        };
        assertOverridesWithoutStartingBeans(source);
    }

    @Test
    void explicitBuilderPropertiesOverrideTheRuntimeDefaults() {
        try (var context = isolatedBuilder().properties(flags(true)).build()) {
            assertFlags(context.getEnvironment().start(), true);
            assertFalse(context.isRunning());
        }
    }

    /** Loads real environment processing while excluding unrelated host and test-resource values. */
    private ApplicationContextBuilder isolatedBuilder() {
        return ApplicationContext.builder()
            .deduceEnvironment(false)
            .environmentPropertySource(false)
            .enableDefaultPropertySources(false);
    }

    private void assertOverridesWithoutStartingBeans(PropertySource source) {
        try (var context = isolatedBuilder().propertySources(source).build()) {
            assertFlags(context.getEnvironment().start(), true);
            assertFalse(context.isRunning());
            assertTrue(context.getEnvironment().getPropertySources().stream()
                .anyMatch(propertySource -> DEFAULT_SOURCE.equals(propertySource.getName())),
                "The host overrides must coexist with the SPI-provided defaults.");
        }
    }

    private void assertFlags(Environment environment, boolean expected) {
        flags(expected).forEach((key, value) ->
            assertEquals(value, environment.getRequiredProperty(key, Boolean.class), key));
    }

    private Map<String, Object> flags(boolean enabled) {
        return Map.of(SEND_EVENT, enabled, DATABASE_LISTENER, enabled, CONSUMERS, enabled);
    }
}
