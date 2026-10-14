package org.flow.repository.postgres;



import org.flow.core.repositories.RepositoryBean;
import org.flow.core.scheduler.model.TriggerState;
import org.flow.jdbc.repository.AbstractJdbcTriggerRepository;

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
