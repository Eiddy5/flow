package org.cses.flow.infrastructure.jooq;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.jooq.Configuration;
import org.x9.jooq.JOOQ;
import org.x9.jooq.JooqFactory;

import javax.sql.DataSource;

/**
 * Creates the Flow JOOQ boundary from the correspondingly named Micronaut
 * data source and jOOQ configuration.
 *
 * <p>The upstream PAAS factory injects an unqualified jOOQ configuration.
 * That is ambiguous as soon as a host application, such as CSES, has more
 * than one data source. This replacement is deliberately scoped to the
 * {@code flow} bean and leaves every host-owned JOOQ bean untouched.</p>
 */
@Factory
@Requires(beans = DataSource.class)
public final class FlowJooqFactory {

    @Singleton
    @Named(FlowDatabase.DATA_SOURCE_NAME)
    @Replaces(
        bean = JOOQ.class,
        factory = JooqFactory.class,
        named = FlowDatabase.DATA_SOURCE_NAME
    )
    JOOQ flowJooq(
        @Named(FlowDatabase.DATA_SOURCE_NAME)
        Configuration configuration,
        @Named(FlowDatabase.DATA_SOURCE_NAME)
        DataSource dataSource
    ) {
        return new JOOQ(
            configuration,
            FlowDataSourceSupport.unwrap(dataSource)
        );
    }
}
