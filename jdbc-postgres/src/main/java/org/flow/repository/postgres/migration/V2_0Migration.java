package org.flow.repository.postgres.migration;

import java.util.List;

import javax.sql.DataSource;
import jakarta.inject.Named;

import org.flow.jdbc.migration.AbstractSQLMigrationScript;
import org.flow.repository.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * OSS PostgreSQL init migration script.
 *
 * <p>
 * Creates the full Flow OSS schema from scratch on fresh PostgreSQL installations.
 * For databases already migrated by Flyway (Flow &le; 1.3), this script is skipped
 * automatically by {@link org.flow.core.migration.MigrationRunner} (schema already exists).
 */
@Singleton
@PostgresRepositoryEnabled
public class V2_0Migration extends AbstractSQLMigrationScript {

    private static final String SCRIPT_ID = "0-init";

    private final DataSource dataSource;

    @Inject
    public V2_0Migration(@Named("flow") final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String scriptId() {
        return SCRIPT_ID;
    }

    @Override
    public String description() {
        return "OSS PostgreSQL init: create full schema from scratch";
    }

    @Override
    public List<String> sqlResources() {
        return List.of("/migrations/baseline-postgres.sql");
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }
}
