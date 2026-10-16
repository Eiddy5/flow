package org.flow.repository.postgres;

import java.util.Map;

import org.jooq.Condition;

import org.flow.core.events.CrudEvent;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.validations.ModelValidator;
import org.flow.core.repositories.RepositoryBean;
import org.flow.core.services.FlowParsingService;
import org.flow.jdbc.repository.AbstractJdbcFlowRepository;

import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresFlowRepository extends AbstractJdbcFlowRepository {
    @Inject
    public PostgresFlowRepository(@Named("flows") PostgresRepository<FlowInterface> repository,
        ModelValidator modelValidator,
        ApplicationEventPublisher<CrudEvent<FlowInterface>> eventPublisher,
        FlowParsingService flowParsingService) {
        super(repository, modelValidator, eventPublisher, flowParsingService);
    }

    /**
     * 生成当前流程表的 PostgreSQL 全文和标签条件。
     *
     * @param query 全文查询文字，可为 null
     * @param labels 标签集合，可为 null
     * @return 合并后的查询条件
     */
    @Override
    protected Condition findCondition(String query, Map<String, String> labels) {
        return PostgresFlowRepositoryService.findCondition(this.jdbcRepository, query, labels);
    }

    /**
     * 通过当前流程表的 JSONB 生成字段应用标签筛选。
     *
     * @param value 标签集合或查询文字
     * @param operation 标签筛选操作
     * @return 标签筛选条件
     */
    @Override
    protected Condition findCondition(Object value, QueryFilter.Op operation) {
        return PostgresFlowRepositoryService.findCondition(table().VALUE, value, operation);
    }

    /**
     * 通过当前流程表的源码生成字段应用全文筛选。
     *
     * @param query 全文查询文字
     * @return 源码全文筛选条件
     */
    @Override
    protected Condition findSourceCodeCondition(String query) {
        return PostgresFlowRepositoryService.findSourceCodeCondition(this.jdbcRepository, query);
    }

    /**
     * 通过当前流程表的 JSONB 生成字段匹配触发器类型。
     *
     * @param triggerClass 触发器类型，为 null 时匹配全部流程
     * @return 触发器类型筛选条件
     */
    @Override
    protected Condition findTriggerClassCondition(Class<? extends org.flow.core.models.triggers.AbstractTrigger> triggerClass) {
        return PostgresFlowRepositoryService.findTriggerClassCondition(table().VALUE, triggerClass);
    }
}
