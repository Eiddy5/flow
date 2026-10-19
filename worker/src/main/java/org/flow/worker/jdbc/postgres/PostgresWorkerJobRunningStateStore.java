package org.flow.worker.jdbc.postgres;

import org.flow.core.jdbc.postgres.PostgresRepository;
import org.flow.core.jdbc.postgres.PostgresRepositoryEnabled;

import org.flow.core.runners.WorkerJobRunning;
import org.flow.worker.jdbc.runner.AbstractJdbcWorkerJobRunningStateStore;

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
