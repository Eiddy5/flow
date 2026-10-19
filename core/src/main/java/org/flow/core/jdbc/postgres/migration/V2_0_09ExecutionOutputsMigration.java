package org.flow.core.jdbc.postgres.migration;

import java.util.List;

import javax.sql.DataSource;
import jakarta.inject.Named;

import org.flow.core.jdbc.migration.AbstractV2_0_09ExecutionOutputsMigration;
import org.flow.core.jdbc.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * OSS PostgreSQL execution outputs table creation migration.
 */
@Singleton
@PostgresRepositoryEnabled
public class V2_0_09ExecutionOutputsMigration extends AbstractV2_0_09ExecutionOutputsMigration {

    private final DataSource dataSource;

    @Inject
    public V2_0_09ExecutionOutputsMigration(@Named("flow") final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/2.0.09-execution-outputs-postgres.sql");
    }
}
