package org.flow.executor.jdbc.postgres;

import org.flow.core.runners.ConcurrencyLimit;
import org.flow.executor.jdbc.runner.AbstractJdbcConcurrencyLimitStateStore;
import org.flow.core.jdbc.postgres.PostgresRepository;
import org.flow.core.jdbc.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@PostgresRepositoryEnabled
public class PostgresConcurrencyLimitStateStore extends AbstractJdbcConcurrencyLimitStateStore {
    public PostgresConcurrencyLimitStateStore(@Named("concurrencylimit") PostgresRepository<ConcurrencyLimit> repository) {
        super(repository);
    }
}
