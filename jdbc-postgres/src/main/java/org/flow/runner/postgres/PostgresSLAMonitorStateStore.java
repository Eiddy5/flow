package org.flow.runner.postgres;

import org.flow.core.models.flows.sla.SLAMonitor;
import org.flow.jdbc.runner.AbstractJdbcSLAMonitorStateStore;
import org.flow.repository.postgres.PostgresRepository;
import org.flow.repository.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@PostgresRepositoryEnabled
public class PostgresSLAMonitorStateStore extends AbstractJdbcSLAMonitorStateStore {
    public PostgresSLAMonitorStateStore(@Named("slamonitor") PostgresRepository<SLAMonitor> repository) {
        super(repository);
    }
}
