package org.flow.indexer.jdbc.postgres;

import org.flow.core.jdbc.postgres.PostgresRepository;

import java.util.List;

import org.jooq.Condition;

import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.plugins.ApplicationContextInitializable;
import org.flow.core.repositories.LogDataStoreInterface;
import org.flow.indexer.jdbc.repository.AbstractJdbcLogDataStore;

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

    /**
     * 使用当前日志库生成表的全文字段，包含独立日志库配置的表名。
     *
     * @param query 全文查询文字
     * @return 日志全文筛选条件
     */
    @Override
    protected Condition findCondition(String query) {
        return this.jdbcRepository.fullTextCondition(List.of(table().FULLTEXT), query);
    }

}
