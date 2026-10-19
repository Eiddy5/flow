package org.flow.scheduler.jdbc.postgres;

import org.flow.core.models.triggers.multipleflows.MultipleConditionWindow;
import org.flow.scheduler.jdbc.runner.AbstractJdbcMultipleConditionStateStore;
import org.flow.core.jdbc.postgres.PostgresRepository;
import org.flow.core.jdbc.postgres.PostgresRepositoryEnabled;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Singleton
@PostgresRepositoryEnabled
public class PostgresMultipleConditionStateStore extends AbstractJdbcMultipleConditionStateStore {
    public PostgresMultipleConditionStateStore(@Named("multipleconditions") PostgresRepository<MultipleConditionWindow> repository) {
        super(repository);
    }
}
