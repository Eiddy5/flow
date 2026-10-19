package org.flow.core.jdbc.postgres;

import org.jooq.Condition;

import org.flow.core.models.kv.PersistedKvMetadata;
import org.flow.core.repositories.RepositoryBean;
import org.flow.core.jdbc.repository.AbstractJdbcKvMetadataRepository;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresKvMetadataRepository extends AbstractJdbcKvMetadataRepository {
    @Inject
    public PostgresKvMetadataRepository(
        @Named("kvMetadata") PostgresRepository<PersistedKvMetadata> repository) {
        super(repository);
    }

    @Override
    protected Condition findCondition(String query) {
        return PostgresKvMetadataRepositoryService.findCondition(jdbcRepository, query);
    }
}
