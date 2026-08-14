package org.cses.flow.infrastructure.jooq;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.jooq.Configuration;
import org.x9.jooq.JOOQ;
import org.x9.jooq.JooqFactory;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Creates the PAAS JOOQ facade with the Configuration for the same datasource.
 *
 * <p>The upstream factory injects {@link Configuration} without an explicit
 * qualifier. That is ambiguous when an embedding application contributes an
 * additional unqualified JOOQ configuration. The datasource name supplied by
 * {@link EachBean} is the stable boundary for selecting the matching Flow or
 * host configuration.</p>
 */
@Factory
@Replaces(factory = JooqFactory.class)
public final class NamedJooqFactory {

    @EachBean(DataSource.class)
    @Prototype
    public JOOQ jooq(
        @Parameter String name,
        DataSource source,
        BeanContext beanContext
    ) {
        Configuration configuration = beanContext.getBean(
            Configuration.class,
            Qualifiers.byName(name)
        );
        if (source.getClass().equals(HikariUrlDataSource.class)) {
            return new JOOQ(configuration, source);
        }
        try {
            return new JOOQ(
                configuration,
                source.unwrap(HikariUrlDataSource.class)
            );
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }
}
