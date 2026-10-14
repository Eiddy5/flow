package org.flow.repository.postgres;

import org.flow.core.lock.Lock;
import org.flow.core.repositories.RepositoryBean;
import org.flow.jdbc.AbstractJdbcRepository;
import org.flow.jdbc.repository.AbstractJdbcLockRepository;

import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresLockRepository extends AbstractJdbcLockRepository {
    public PostgresLockRepository(@Named("locks") AbstractJdbcRepository<Lock> jdbcRepository) {
        super(jdbcRepository);
    }
}
