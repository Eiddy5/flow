package org.flow.core.jdbc.postgres.migration;

import java.util.List;

import javax.sql.DataSource;
import jakarta.inject.Named;

import org.flow.core.jdbc.migration.AbstractV2_0_01SchemaMigration;
import org.flow.core.jdbc.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * PostgreSQL Flow 2.0 schema upgrade migration.
 */
@Singleton
@PostgresRepositoryEnabled
public class V2_0_01SchemaMigration extends AbstractV2_0_01SchemaMigration {

    private final DataSource dataSource;

    @Inject
    public V2_0_01SchemaMigration(@Named("flow") final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.01-schema-postgres.sql");
    }
}
