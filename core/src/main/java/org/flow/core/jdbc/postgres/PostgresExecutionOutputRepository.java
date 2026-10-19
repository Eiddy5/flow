package org.flow.core.jdbc.postgres;

import org.flow.core.models.executions.ExecutionOutput;
import org.flow.core.repositories.RepositoryBean;
import org.flow.core.jdbc.repository.AbstractJdbcExecutionOutputRepository;

import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresExecutionOutputRepository extends AbstractJdbcExecutionOutputRepository {
    public PostgresExecutionOutputRepository(@Named("executionoutputs") PostgresRepository<ExecutionOutput> jdbcRepository) {
        super(jdbcRepository);
    }
}
