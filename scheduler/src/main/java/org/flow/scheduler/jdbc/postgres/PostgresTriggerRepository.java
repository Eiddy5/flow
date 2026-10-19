package org.flow.scheduler.jdbc.postgres;

import org.flow.core.jdbc.postgres.PostgresRepository;
import org.flow.core.jdbc.postgres.PostgresRepositoryEnabled;

import org.flow.core.repositories.RepositoryBean;
import org.flow.core.scheduler.model.TriggerState;
import org.flow.scheduler.jdbc.repository.AbstractJdbcTriggerRepository;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresTriggerRepository extends AbstractJdbcTriggerRepository {
    @Inject
    public PostgresTriggerRepository(@Named("triggers") PostgresRepository<TriggerState> repository) {
        super(repository);
    }

}
