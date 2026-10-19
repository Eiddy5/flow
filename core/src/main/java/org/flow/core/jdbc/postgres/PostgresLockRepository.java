package org.flow.core.jdbc.postgres;

import org.flow.core.lock.Lock;
import org.flow.core.repositories.RepositoryBean;
import org.flow.core.jdbc.AbstractJdbcRepository;
import org.flow.core.jdbc.repository.AbstractJdbcLockRepository;

import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresLockRepository extends AbstractJdbcLockRepository {
    public PostgresLockRepository(@Named("locks") AbstractJdbcRepository<Lock> jdbcRepository) {
        super(jdbcRepository);
    }
}
