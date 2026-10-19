package org.flow.executor.jdbc.runner;

import org.flow.core.jdbc.runner.JdbcTransactionContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import org.jooq.Field;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.ExecutionQueuedTable;
import org.flow.core.models.executions.Execution;
import org.flow.core.runners.ExecutionQueued;
import org.flow.core.runners.ExecutionQueuedStateStore;
import org.flow.core.runners.TransactionContext;
import org.flow.core.utils.IdUtils;
import org.flow.core.jdbc.repository.AbstractJdbcRepository;

public abstract class AbstractJdbcExecutionQueuedStateStore extends AbstractJdbcRepository implements ExecutionQueuedStateStore {
    protected org.flow.core.jdbc.AbstractJdbcRepository<ExecutionQueued> jdbcRepository;

    /**
     * 返回当前存储使用的生成表，保留配置的物理名称和字段类型。
     *
     * @return 与当前 JDBC Repository 绑定的生成表
     */
    @Override
    protected ExecutionQueuedTable table() {
        return jdbcRepository.getTable(Tables.EXECUTION_QUEUED);
    }

    public AbstractJdbcExecutionQueuedStateStore(org.flow.core.jdbc.AbstractJdbcRepository<ExecutionQueued> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    /**
     * 在调用方的 JDBC 事务中保存排队执行状态。
     *
     * @param txContext 调用方传入的事务上下文；不能为 null
     * @param executionQueued 待保存的排队执行状态；不能为 null
     */
    @Override
    public void save(TransactionContext txContext, ExecutionQueued executionQueued) {
        Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(executionQueued);
        this.jdbcRepository.persist(executionQueued, txContext.unwrap(JdbcTransactionContext.class).getDslContext(), fields);
    }

    /**
     * 按时间领取最早的一条排队执行，跳过已锁记录；在当前事务调用回调后删除状态。
     *
     * @param txContext 调用方传入的事务上下文；不能为 null
     * @param tenantId 租户标识；null 表示无租户记录
     * @param namespace 需要匹配的命名空间；不能为 null
     * @param flowId 需要匹配的流程标识；不能为 null
     * @param consumer 处理查询结果的回调；不能为 null，异常会向调用方传播
     */
    @Override
    public void pop(TransactionContext txContext, String tenantId, String namespace, String flowId, BiConsumer<TransactionContext, Execution> consumer) {
        var dslContext = txContext.unwrap(JdbcTransactionContext.class).getDslContext();
        var select = dslContext
            .select(table().VALUE)
            .from(table())
            .where(buildTenantCondition(tenantId))
            .and(table().NAMESPACE.eq(namespace))
            .and(table().FLOW_ID.eq(flowId))
            .orderBy(table().DATE.asc())
            .limit(1)
            .forUpdate()
            .skipLocked();

        Optional<ExecutionQueued> maybeExecution = this.jdbcRepository.fetchOne(select);
        if (maybeExecution.isPresent()) {
            consumer.accept(txContext, maybeExecution.get().getExecution());
            this.jdbcRepository.delete(maybeExecution.get());
        }
    }

    /**
     * 读取所有租户的排队执行状态，供管理操作使用。
     *
     * @return 全部排队状态；无记录时为空
     */
    public List<ExecutionQueued> getAllForAllTenants() {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table());

                return this.jdbcRepository.fetch(select);
            });
    }

    /**
     * 按执行身份和租户删除对应的排队状态。
     *
     * @param execution 需要移出排队存储的执行；不能为 null
     */
    @Override
    public void remove(Execution execution) {
        this.jdbcRepository
            .getDslContextWrapper()
            .transaction(configuration ->
            {
                DSL
                    .using(configuration)
                    .deleteFrom(table())
                    .where(buildTenantCondition(execution.getTenantId()))
                    .and(table().KEY.eq(IdUtils.fromParts(execution.getTenantId(), execution.getNamespace(), execution.getFlowId(), execution.getId())))
                    .execute();
            });
    }
}
