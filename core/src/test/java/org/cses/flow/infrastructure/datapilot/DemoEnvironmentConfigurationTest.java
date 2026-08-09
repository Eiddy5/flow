package org.cses.flow.infrastructure.datapilot;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DemoEnvironmentConfigurationTest {

    @Test
    void standaloneDemoRegistersItsPostgresAdapter() {
        try (ApplicationContext context = startContext(Map.of())) {
            assertUnifiedDemoConfiguration(context);
            assertTrue(context.getEnvironment().getProperty(
                "flow.demo.postgres.url",
                String.class
            ).orElseThrow().startsWith("jdbc:postgresql://"));
            assertFalse(context.getEnvironment().getProperty(
                "flow.demo.platform-managed",
                Boolean.class
            ).orElseThrow());
            assertTrue(context.containsBean(
                DemoPostgresJooqAdapter.class
            ));
        }
    }

    @Test
    void platformManagedDemoDoesNotRegisterStandaloneAdapter() {
        try (ApplicationContext context = startContext(Map.of(
            "flow.demo.platform-managed",
            true
        ))) {
            assertUnifiedDemoConfiguration(context);
            assertTrue(context.getEnvironment().getProperty(
                "flow.demo.platform-managed",
                Boolean.class
            ).orElseThrow());
            assertFalse(context.containsBean(
                DemoPostgresJooqAdapter.class
            ));
        }
    }

    private static void assertUnifiedDemoConfiguration(
        ApplicationContext context
    ) {
        assertTrue(context.getEnvironment().getProperty(
            "flow.demo.enabled",
            Boolean.class
        ).orElseThrow());
        assertTrue(context.getEnvironment().getProperty(
            "flow.demo.session-binder.enabled",
            Boolean.class
        ).orElseThrow());
    }

    private static ApplicationContext startContext(
        Map<String, Object> overrides
    ) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("flow.memory.enabled", false);
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.datasources.default.enabled", false);
        properties.put("micronaut.config-client.enabled", false);
        properties.put("consul.client.registration.enabled", false);
        properties.put("consul.client.config.enabled", false);
        properties.put(
            "consul.client.watch.service.enabled",
            false
        );
        properties.put("grpc.server.enabled", false);
        properties.put("grpc.server.health.enabled", false);
        properties.put("thrift.server.enabled", false);
        properties.put("pulsar.consumer.enabled", false);
        properties.putAll(overrides);
        return ApplicationContext.run(
            properties,
            "demo",
            "test"
        );
    }
}
