package org.flow.runner.postgres;

import org.flow.core.models.triggers.multipleflows.MultipleConditionWindow;
import org.flow.jdbc.runner.AbstractJdbcMultipleConditionStateStore;
import org.flow.repository.postgres.PostgresRepository;
import org.flow.repository.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@PostgresRepositoryEnabled
public class PostgresMultipleConditionStateStore extends AbstractJdbcMultipleConditionStateStore {
    public PostgresMultipleConditionStateStore(@Named("multipleconditions") PostgresRepository<MultipleConditionWindow> repository) {
        super(repository);
    }
}
