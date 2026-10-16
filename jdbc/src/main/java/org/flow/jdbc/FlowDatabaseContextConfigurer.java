package org.flow.jdbc;

import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.context.annotation.ContextConfigurer;
import io.micronaut.context.env.PropertySource;

import java.util.Map;

/** Keeps the generated-object integration usable without a messaging service. */
@ContextConfigurer
public class FlowDatabaseContextConfigurer implements ApplicationContextConfigurer {
    /**
     * Supplies only defaults: host YAML, environment variables and builder properties take precedence.
     * Internal jOOQ/PaaS beans require Pulsar types, but Flow uses its own JDBC queue protocol.
     */
    @Override
    public void configure(ApplicationContextBuilder builder) {
        builder.propertySources(PropertySource.of("flow-jdbc-runtime-defaults", Map.of(
            "jooq.send-event", false,
            "table.db-change.listeners.pulsar.enabled", false,
            "pulsar.consumer.enabled", false
        ), Integer.MIN_VALUE));
    }
}
