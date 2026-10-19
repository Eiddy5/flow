package org.flow.core.jdbc.postgres;

import org.jooq.Condition;

import org.flow.core.models.namespaces.files.NamespaceFileMetadata;
import org.flow.core.repositories.RepositoryBean;
import org.flow.core.jdbc.repository.AbstractJdbcNamespaceFileMetadataRepository;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresNamespaceFileMetadataRepository extends AbstractJdbcNamespaceFileMetadataRepository {
    @Inject
    public PostgresNamespaceFileMetadataRepository(
        @Named("namespaceFileMetadata") PostgresRepository<NamespaceFileMetadata> repository) {
        super(repository);
    }

    @Override
    protected Condition findCondition(String query) {
        return PostgresNamespaceFileMetadataRepositoryService.findCondition(jdbcRepository, query);
    }
}
