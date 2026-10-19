package org.flow.core.jdbc.postgres;

import org.flow.core.models.executions.TaskOutput;
import org.flow.core.repositories.RepositoryBean;
import org.flow.core.jdbc.repository.AbstractJdbcTaskOutputRepository;

import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresTaskOutputRepository extends AbstractJdbcTaskOutputRepository {
    public PostgresTaskOutputRepository(@Named("taskoutputs") PostgresRepository<TaskOutput> jdbcRepository) {
        super(jdbcRepository);
    }
}
