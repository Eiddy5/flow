package org.flow.executor.jdbc.postgres;

import org.flow.core.models.flows.sla.SLAMonitor;
import org.flow.executor.jdbc.runner.AbstractJdbcSLAMonitorStateStore;
import org.flow.core.jdbc.postgres.PostgresRepository;
import org.flow.core.jdbc.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@PostgresRepositoryEnabled
public class PostgresSLAMonitorStateStore extends AbstractJdbcSLAMonitorStateStore {
    public PostgresSLAMonitorStateStore(@Named("slamonitor") PostgresRepository<SLAMonitor> repository) {
        super(repository);
    }
}
