package org.flow.jdbc.runner;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.MultipleconditionsTable;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.FlowId;
import org.flow.core.models.triggers.multipleflows.MultipleCondition;
import org.flow.core.models.triggers.multipleflows.MultipleConditionStateStore;
import org.flow.core.models.triggers.multipleflows.MultipleConditionWindow;
import org.flow.core.runners.TransactionContext;
import org.flow.jdbc.repository.AbstractJdbcRepository;

public abstract class AbstractJdbcMultipleConditionStateStore extends AbstractJdbcRepository implements MultipleConditionStateStore {
    protected org.flow.jdbc.AbstractJdbcRepository<MultipleConditionWindow> jdbcRepository;

    /**
     * 返回当前存储使用的生成表，保留配置的物理名称和字段类型。
     *
     * @return 与当前 JDBC Repository 绑定的生成表
     */
    @Override
    protected MultipleconditionsTable table() {
        return jdbcRepository.getTable(Tables.MULTIPLECONDITIONS);
    }

    public AbstractJdbcMultipleConditionStateStore(org.flow.jdbc.AbstractJdbcRepository<MultipleConditionWindow> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    @Override
    public Execution process(FlowId flow, MultipleCondition multipleCondition, Map<String, Object> outputs, BiFunction<TransactionContext, MultipleConditionWindow, Execution> consumer) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext dslContext = DSL.using(configuration);

                var multipleConditionWindow = this.jdbcRepository.getOrInsert(
                    dslContext,
                    () -> get(dslContext, flow, multipleCondition.getId()),
                    () -> create(flow, multipleCondition, outputs)
                );

                var txContext = new JdbcTransactionContext(dslContext);
                Execution newExecution = consumer.apply(txContext, multipleConditionWindow);

                if (newExecution != null) {
                    // re-fetch within the same transaction to get the state after the consumer's save(),
                    // then delete if all conditions are now satisfied
                    get(dslContext, flow, multipleCondition.getId())
                        .filter(multipleCondition::isConditionSatisfied)
                        .ifPresent(w -> delete(dslContext, w));
                }

                return newExecution;
            });
    }

    /**
     * 按流程身份和条件标识读取窗口，并在查询事务中锁定匹配记录。
     *
     * @param flow 携带租户、命名空间和标识的流程；不能为 null
     * @param conditionId 需要匹配的多条件标识；不能为 null
     * @return 匹配窗口；未找到时为空
     */
    @Override
    public Optional<MultipleConditionWindow> get(FlowId flow, String conditionId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext dslContext = DSL.using(configuration);
                return this.get(dslContext, flow, conditionId);
            });
    }

    /**
     * 按流程身份和条件标识读取窗口，并在查询事务中锁定匹配记录。
     *
     * @param dslContext 当前事务使用的 DSLContext；不能为 null
     * @param flow 携带租户、命名空间和标识的流程；不能为 null
     * @param conditionId 需要匹配的多条件标识；不能为 null
     * @return 匹配窗口；未找到时为空
     */
    private Optional<MultipleConditionWindow> get(DSLContext dslContext, FlowId flow, String conditionId) {
        var select = dslContext
            .select(table().VALUE)
            .from(table())
            .where(
                table().NAMESPACE.eq(flow.getNamespace())
                    .and(buildTenantCondition(flow.getTenantId()))
                    .and(table().FLOW_ID.eq(flow.getId()))
                    .and(table().CONDITION_ID.eq(conditionId))
            )
            .forUpdate();

        return this.jdbcRepository.fetchOne(select);
    }

    /**
     * 读取给定租户中结束时间早于当前时间的多条件窗口。
     *
     * @param tenantId 租户标识；null 表示无租户记录
     * @return 已到期窗口；无记录时为空
     */
    @Override
    public List<MultipleConditionWindow> expired(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(
                        getEndDataCondition().and(buildTenantCondition(tenantId))
                    );

                return this.jdbcRepository.fetch(select);
            });
    }

    /**
     * 使用生成的结束时间字段构造早于当前时间的 SQL 条件。
     *
     * @return 多条件窗口到期条件
     */
    protected Condition getEndDataCondition() {
        return table().END_DATE.lt(Instant.now().atOffset(ZoneOffset.UTC));
    }

    /**
     * 在 JDBC 事务中保存多条件窗口；带事务上下文的重载复用调用方事务。
     *
     * @param txContext 调用方传入的事务上下文；不能为 null
     * @param multipleConditionWindow 待保存的多条件窗口；不能为 null
     */
    @Override
    public void save(TransactionContext txContext, MultipleConditionWindow multipleConditionWindow) {
        var dslContext = txContext.unwrap(JdbcTransactionContext.class).getDslContext();
        Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(multipleConditionWindow);
        this.jdbcRepository.persist(multipleConditionWindow, dslContext, fields);
    }

    /**
     * 在 JDBC 事务中保存多条件窗口；带事务上下文的重载复用调用方事务。
     *
     * @param multipleConditionWindow 待保存的多条件窗口；不能为 null
     */
    @Override
    public void save(MultipleConditionWindow multipleConditionWindow) {
        this.jdbcRepository.getDslContextWrapper().transaction(configuration ->
        {
            DSLContext dslContext = DSL.using(configuration);
            save(new JdbcTransactionContext(dslContext), multipleConditionWindow);
        });
    }

    @Override
    public void delete(MultipleConditionWindow multipleConditionWindow) {
        this.jdbcRepository.delete(multipleConditionWindow);
    }

    private void delete(DSLContext dslContext, MultipleConditionWindow multipleConditionWindow) {
        this.jdbcRepository.delete(dslContext, multipleConditionWindow);
    }
}
