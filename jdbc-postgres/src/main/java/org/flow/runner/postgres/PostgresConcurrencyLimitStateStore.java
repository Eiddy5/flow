package org.flow.runner.postgres;

import org.flow.core.runners.ConcurrencyLimit;
import org.flow.jdbc.runner.AbstractJdbcConcurrencyLimitStateStore;
import org.flow.repository.postgres.PostgresRepository;
import org.flow.repository.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@PostgresRepositoryEnabled
public class PostgresConcurrencyLimitStateStore extends AbstractJdbcConcurrencyLimitStateStore {
    public PostgresConcurrencyLimitStateStore(@Named("concurrencylimit") PostgresRepository<ConcurrencyLimit> repository) {
        super(repository);
    }
}
