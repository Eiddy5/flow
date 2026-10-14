package org.flow.runner.postgres;

import org.flow.core.runners.ExecutionDelay;
import org.flow.jdbc.runner.AbstractJdbcExecutionDelayStateStore;
import org.flow.repository.postgres.PostgresRepository;
import org.flow.repository.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@PostgresRepositoryEnabled
public class PostgresExecutionDelayStateStore extends AbstractJdbcExecutionDelayStateStore {
    public PostgresExecutionDelayStateStore(@Named("executordelayed") PostgresRepository<ExecutionDelay> repository) {
        super(repository);
    }
}
