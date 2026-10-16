package org.flow.repository.postgres;

import java.util.*;

import org.jooq.Condition;

import org.flow.core.contexts.configuration.SystemFlowsConfiguration;
import org.flow.core.events.CrudEvent;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.executions.Execution;
import org.flow.core.repositories.RepositoryBean;
import org.flow.core.utils.Either;
import org.flow.jdbc.repository.AbstractJdbcExecutionRepository;

import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresExecutionRepository extends AbstractJdbcExecutionRepository {
    @Inject
    public PostgresExecutionRepository(@Named("executions") PostgresRepository<Execution> repository,
        ApplicationEventPublisher<CrudEvent<Execution>> eventPublisher,
        SystemFlowsConfiguration systemFlowsConfiguration) {
        super(repository, eventPublisher, systemFlowsConfiguration);
    }

    /**
     * 生成当前执行表的 PostgreSQL 全文和标签条件。
     *
     * @param query 全文查询文字，可为 null
     * @param labels 标签集合，可为 null
     * @return 合并后的查询条件
     */
    @Override
    protected Condition findCondition(String query, Map<String, String> labels) {
        return PostgresExecutionRepositoryService.findCondition(this.jdbcRepository, query, labels);
    }

    /**
     * 通过当前执行表的 JSONB 生成字段应用标签筛选。
     *
     * @param input 标签集合或查询文字
     * @param operation 标签筛选操作
     * @return 标签筛选条件
     */
    @Override
    public Condition findLabelCondition(Either<Map<?, ?>, String> input, QueryFilter.Op operation) {
        return PostgresExecutionRepositoryService.findLabelCondition(table().VALUE, input, operation);
    }

}
