package org.flow.runner.postgres;

import org.flow.core.runners.ExecutionQueued;
import org.flow.jdbc.runner.AbstractJdbcExecutionQueuedStateStore;
import org.flow.repository.postgres.PostgresRepository;
import org.flow.repository.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@PostgresRepositoryEnabled
public class PostgresExecutionQueuedStateStore extends AbstractJdbcExecutionQueuedStateStore {
    public PostgresExecutionQueuedStateStore(@Named("executionqueued") PostgresRepository<ExecutionQueued> repository) {
        super(repository);
    }
}
