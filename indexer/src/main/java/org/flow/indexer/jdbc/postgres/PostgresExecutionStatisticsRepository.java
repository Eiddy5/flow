package org.flow.indexer.jdbc.postgres;

import org.flow.core.jdbc.postgres.PostgresRepository;
import org.flow.core.jdbc.postgres.PostgresRepositoryEnabled;

import org.flow.core.models.executions.statistics.ExecutionStatistic;
import org.flow.core.repositories.RepositoryBean;
import org.flow.indexer.jdbc.repository.AbstractJdbcExecutionStatisticsRepository;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresExecutionStatisticsRepository extends AbstractJdbcExecutionStatisticsRepository {
    @Inject
    public PostgresExecutionStatisticsRepository(@Named("executionstatistics") PostgresRepository<ExecutionStatistic> repository) {
        super(repository);
    }
}
