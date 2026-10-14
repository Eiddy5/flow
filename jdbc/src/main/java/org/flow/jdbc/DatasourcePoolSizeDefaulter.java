package org.flow.jdbc;

import java.util.Objects;

import io.micronaut.configuration.jdbc.hikari.DatasourceConfiguration;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Raises HikariCP's default maximum pool size (10) to a value better suited to the JDBC queue,
 * which polls with one dedicated thread per queue type/routing key and can hold a connection for
 * the duration of message processing. Only applies when the user has not explicitly configured
 * {@code datasources.flow.maximum-pool-size} themselves. Other application datasources are
 * left unchanged, and this listener never creates a datasource.
 * <p>
 * Also, default the idle size to half that value as otherwise HikariCP will use the maximum-pool-size.
 * <p>
 * To balance performance with resource usage, we raise the default to {@link #DEFAULT_MAXIMUM_POOL_SIZE}.
 */
@Singleton
public class DatasourcePoolSizeDefaulter implements BeanCreatedEventListener<DatasourceConfiguration> {
    private static final int DEFAULT_MAXIMUM_POOL_SIZE = 20;

    private final Environment environment;

    /**
     * Retains the environment used to distinguish explicit pool settings from defaults.
     * @param environment application configuration, must not be null
     */
    @Inject
    public DatasourcePoolSizeDefaulter(Environment environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    /**
     * Applies queue-oriented defaults only to the flow pool, preserving explicit settings.
     * @param event datasource configuration being created
     * @return the same configuration, with defaults applied when appropriate
     */
    @Override
    public DatasourceConfiguration onCreated(BeanCreatedEvent<DatasourceConfiguration> event) {
        DatasourceConfiguration configuration = event.getBean();
        if (!"flow".equals(configuration.getName())) {
            return configuration;
        }

        if (!environment.containsProperty("datasources." + configuration.getName() + ".maximum-pool-size")) {
            configuration.setMaximumPoolSize(DEFAULT_MAXIMUM_POOL_SIZE);

            if (!environment.containsProperty("datasources." + configuration.getName() + ".minimum-idle")) {
                configuration.setMinimumIdle(DEFAULT_MAXIMUM_POOL_SIZE / 2);
            }
        }

        return configuration;
    }
}
