package org.flow.indexer.jdbc.postgres.migration;

import org.flow.indexer.jdbc.LogJdbcDataSourceProvider;
import org.flow.indexer.jdbc.migration.AbstractV2_0_06WidenLogsMigration;
import org.flow.indexer.jdbc.migration.LogStoreTypeResolver;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import jakarta.inject.Singleton;

/**
 * Postgres log-store task_id/trigger_id widening migration.
 *
 * <p>
 * Runs whenever the effective log-store dialect is Postgres — a configured {@code flow.logs.type=postgres}
 * or the fallback to a Postgres {@code flow.repository.type} (logs kept in the main database).
 */
@Singleton
@Requires(condition = V2_0_06WidenLogsMigration.PostgresLogStoreEnabled.class)
public class V2_0_06WidenLogsMigration extends AbstractV2_0_06WidenLogsMigration {

    public static final class PostgresLogStoreEnabled implements Condition {
        @Override
        public boolean matches(final ConditionContext context) {
            return LogStoreTypeResolver.matches(context, "postgres");
        }
    }

    public V2_0_06WidenLogsMigration(final LogJdbcDataSourceProvider logDataSourceProvider) {
        super("postgres", "/migrations/2.0.06-widen-logs-postgres.sql", logDataSourceProvider);
    }
}
