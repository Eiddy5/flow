package org.flow.executor.jdbc.postgres;

import org.flow.core.runners.ExecutionQueued;
import org.flow.executor.jdbc.runner.AbstractJdbcExecutionQueuedStateStore;
import org.flow.core.jdbc.postgres.PostgresRepository;
import org.flow.core.jdbc.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@PostgresRepositoryEnabled
public class PostgresExecutionQueuedStateStore extends AbstractJdbcExecutionQueuedStateStore {
    public PostgresExecutionQueuedStateStore(@Named("executionqueued") PostgresRepository<ExecutionQueued> repository) {
        super(repository);
    }
}
