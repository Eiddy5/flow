package org.flow.repository.postgres;

import org.flow.core.runners.WorkerJobRunning;
import org.flow.jdbc.runner.AbstractJdbcWorkerJobRunningStateStore;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@PostgresRepositoryEnabled
public class PostgresWorkerJobRunningStateStore extends AbstractJdbcWorkerJobRunningStateStore {
    @Inject
    public PostgresWorkerJobRunningStateStore(@Named("workerjobrunning") PostgresRepository<WorkerJobRunning> repository) {
        super(repository);
    }
}
