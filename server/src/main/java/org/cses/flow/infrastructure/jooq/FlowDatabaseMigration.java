package org.cses.flow.infrastructure.jooq;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.naming.NameResolver;
import jakarta.inject.Singleton;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

import javax.sql.DataSource;

/**
 * Migrates only the named Flow data source before it becomes available to
 * JOOQ and the rest of the application context.
 */
@Singleton
@Requires(
    property = "flow.database.migration.enabled",
    notEquals = "false",
    defaultValue = "true"
)
public final class FlowDatabaseMigration
    implements BeanCreatedEventListener<DataSource> {

    @Override
    public DataSource onCreated(BeanCreatedEvent<DataSource> event) {
        if (!isFlowDataSource(event)) {
            return event.getBean();
        }

        try {
            Flyway.configure()
                .dataSource(FlowDataSourceSupport.unwrap(event.getBean()))
                .locations(FlowDatabase.MIGRATION_LOCATION)
                .table(FlowDatabase.SCHEMA_HISTORY_TABLE)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load()
                .migrate();
        } catch (FlywayException exception) {
            throw new IllegalStateException(
                "Cannot migrate the Flow data source '"
                    + FlowDatabase.DATA_SOURCE_NAME
                    + "'",
                exception
            );
        }
        return event.getBean();
    }

    private static boolean isFlowDataSource(
        BeanCreatedEvent<DataSource> event
    ) {
        if (!(event.getBeanDefinition() instanceof NameResolver resolver)) {
            return false;
        }
        return resolver.resolveName()
            .filter(FlowDatabase.DATA_SOURCE_NAME::equals)
            .isPresent();
    }
}
