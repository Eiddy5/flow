package org.cses.flow.infrastructure.jooq;

import io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Resolves the physical Flow pool behind Micronaut's transaction-aware
 * data-source proxy.
 */
final class FlowDataSourceSupport {

    private FlowDataSourceSupport() {
    }

    static DataSource unwrap(DataSource dataSource) {
        if (dataSource.getClass().equals(HikariUrlDataSource.class)) {
            return dataSource;
        }
        try {
            return dataSource.unwrap(HikariUrlDataSource.class);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Cannot unwrap the Flow data source",
                exception
            );
        }
    }
}
