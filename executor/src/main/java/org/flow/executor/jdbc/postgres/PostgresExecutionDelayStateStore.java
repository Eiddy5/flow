package org.flow.executor.jdbc.postgres;

import org.flow.core.runners.ExecutionDelay;
import org.flow.executor.jdbc.runner.AbstractJdbcExecutionDelayStateStore;
import org.flow.core.jdbc.postgres.PostgresRepository;
import org.flow.core.jdbc.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@PostgresRepositoryEnabled
public class PostgresExecutionDelayStateStore extends AbstractJdbcExecutionDelayStateStore {
    public PostgresExecutionDelayStateStore(@Named("executordelayed") PostgresRepository<ExecutionDelay> repository) {
        super(repository);
    }
}
