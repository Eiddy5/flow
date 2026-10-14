package org.flow.repository.postgres;

import org.flow.core.repositories.RepositoryBean;
import org.flow.core.runners.ConcurrencyLimit;
import org.flow.jdbc.repository.AbstractJdbcConcurrencyLimitRepository;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresJdbcConcurrencyLimitRepository extends AbstractJdbcConcurrencyLimitRepository {
    @Inject
    public PostgresJdbcConcurrencyLimitRepository(@Named("concurrencylimit") PostgresRepository<ConcurrencyLimit> jdbcRepository) {
        super(jdbcRepository);
    }
}
