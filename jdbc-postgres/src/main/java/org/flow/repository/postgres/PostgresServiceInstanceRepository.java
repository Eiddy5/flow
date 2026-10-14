package org.flow.repository.postgres;

import java.util.List;

import org.jooq.Condition;

import org.flow.core.repositories.RepositoryBean;
import org.flow.core.server.ServiceInstance;
import org.flow.jdbc.repository.AbstractJdbcServiceInstanceRepository;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresServiceInstanceRepository extends AbstractJdbcServiceInstanceRepository {
    @Inject
    public PostgresServiceInstanceRepository(@Named("serviceinstance") PostgresRepository<ServiceInstance> repository) {
        super(repository);
    }

    @Override
    protected Condition findQueryCondition(String query) {
        return jdbcRepository.fullTextCondition(List.of("fulltext"), query);
    }
}
