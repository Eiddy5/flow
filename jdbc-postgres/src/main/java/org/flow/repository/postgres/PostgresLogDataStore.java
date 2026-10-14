package org.flow.repository.postgres;

import java.util.Collections;
import java.util.List;

import org.jooq.Condition;
import org.slf4j.event.Level;

import org.flow.core.models.annotations.Plugin;
import org.flow.core.plugins.ApplicationContextInitializable;
import org.flow.core.repositories.LogDataStoreInterface;
import org.flow.jdbc.repository.AbstractJdbcLogDataStore;

import io.micronaut.context.ApplicationContext;

/**
 * PostgreSQL {@link LogDataStoreInterface} log store, selected by {@code flow.logs.type: postgres}.
 * <p>
 * Deserialized from configuration by {@code LogDataStoreInterfaceFactory}, then wires its runtime
 * dependencies in {@link #init(ApplicationContext)}: a dedicated Postgres repository when
 * {@code flow.logs.postgres.url} is set, otherwise the shared {@code @Named("logs")} repository.
 */
@Plugin
@Plugin.Id("postgres")
public class PostgresLogDataStore extends AbstractJdbcLogDataStore implements ApplicationContextInitializable {

    public PostgresLogDataStore() {
        super();
    }

    @Override
    public void init(ApplicationContext applicationContext) {
        initFrom(applicationContext, (tableConfig, wrapper) -> new PostgresRepository<>(tableConfig, wrapper));
    }

    @Override
    protected Condition findCondition(String query) {
        return this.jdbcRepository.fullTextCondition(Collections.singletonList("fulltext"), query);
    }

    @Override
    protected Condition levelsCondition(List<Level> levels) {
        return PostgresLogRepositoryService.levelsCondition(levels);
    }

    @Override
    protected Condition notLevelsCondition(List<Level> levels) {
        return PostgresLogRepositoryService.notLevelsCondition(levels);
    }

}
