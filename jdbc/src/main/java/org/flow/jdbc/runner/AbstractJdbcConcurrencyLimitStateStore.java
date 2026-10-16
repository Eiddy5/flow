package org.flow.jdbc.runner;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.ConcurrencyLimitTable;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.runners.ConcurrencyLimit;
import org.flow.core.runners.ExecutionQueuedStateStore;
import org.flow.core.runners.ExecutionRunning;
import org.flow.core.runners.TransactionContext;
import org.flow.core.utils.IdUtils;
import org.flow.executor.ConcurrencyLimitStateStore;
import org.flow.jdbc.repository.AbstractJdbcRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AbstractJdbcConcurrencyLimitStateStore extends AbstractJdbcRepository implements ConcurrencyLimitStateStore {
    protected org.flow.jdbc.AbstractJdbcRepository<ConcurrencyLimit> jdbcRepository;

    /**
     * 返回当前存储使用的生成表，保留配置的物理名称和字段类型。
     *
     * @return 与当前 JDBC Repository 绑定的生成表
     */
    @Override
    protected ConcurrencyLimitTable table() {
        return jdbcRepository.getTable(Tables.CONCURRENCY_LIMIT);
    }

    public AbstractJdbcConcurrencyLimitStateStore(org.flow.jdbc.AbstractJdbcRepository<ConcurrencyLimit> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    /**
     * Fetch the concurrency limit counter, then process the count using the consumer function.
     * It locked the raw and is wrapped in a transaction, so the consumer should use the provided dslContext for any database access.
     * <p>
     * Note that to avoid a race when no concurrency limit counter exists, it first always tries to insert a 0 counter.
     */
    @Override
    public ExecutionRunning countThenProcess(FlowInterface flow, BiFunction<TransactionContext, ConcurrencyLimit, Pair<ExecutionRunning, ConcurrencyLimit>> consumer) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var dslContext = DSL.using(configuration);

                var selected = this.jdbcRepository.getOrInsert(
                    dslContext,
                    () -> fetchOne(dslContext, flow),
                    () -> ConcurrencyLimit.builder()
                        .tenantId(flow.getTenantId())
                        .namespace(flow.getNamespace())
                        .flowId(flow.getId())
                        .running(0)
                        .build()
                );

                var txContext = new JdbcTransactionContext(dslContext);
                var pair = consumer.apply(txContext, selected);
                update(dslContext, pair.getRight());
                return pair.getLeft();
            });
    }

    /**
     * Decrement the concurrency limit counter.
     * Must only be called when a flow having concurrency limit ends.
     */
    @Override
    public int decrement(FlowInterface flow) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var dslContext = DSL.using(configuration);

                return fetchOne(dslContext, flow).map(
                    concurrencyLimit ->
                    {
                        int newLimit = concurrencyLimit.getRunning() == 0 ? 0 : concurrencyLimit.getRunning() - 1;
                        update(dslContext, concurrencyLimit.withRunning(newLimit));
                        return newLimit;
                    }
                ).orElse(0);
            });
    }

    @Override
    public void decrementAndPop(FlowInterface flow, ExecutionQueuedStateStore executionQueuedStateStore,
        BiConsumer<TransactionContext, Execution> consumer) {
        this.jdbcRepository
            .getDslContextWrapper()
            .transaction(configuration ->
            {
                var dslContext = DSL.using(configuration);

                // Decrement the counter
                int newLimit = fetchOne(dslContext, flow).map(
                    concurrencyLimit ->
                    {
                        int decremented = concurrencyLimit.getRunning() == 0 ? 0 : concurrencyLimit.getRunning() - 1;
                        update(dslContext, concurrencyLimit.withRunning(decremented));
                        return decremented;
                    }
                ).orElse(0);

                // Only pop if we're below the limit
                if (newLimit < flow.getConcurrency().getLimit()) {
                    executionQueuedStateStore.pop(
                        new JdbcTransactionContext(dslContext),
                        flow.getTenantId(),
                        flow.getNamespace(),
                        flow.getId(),
                        (ctx, queued) ->
                        {
                            // Increment the counter for the newly running execution
                            increment(ctx, flow);
                            // Call the consumer
                            consumer.accept(ctx, queued);
                        }
                    );
                } else {
                    log.error(
                        "Concurrency limit reached for flow {}.{} after decrementing the execution running count. No new executions will be dequeued.", flow.getNamespace(), flow.getId()
                    );
                }
            });
    }

    /**
     * Increment the concurrency limit counter.
     * Must only be called when a queued execution is popped, other use cases must pass thought the standard process of creating an execution.
     */
    @Override
    public void increment(TransactionContext txContext, FlowInterface flow) {
        var dslContext = txContext.unwrap(JdbcTransactionContext.class).getDslContext();
        fetchOne(dslContext, flow).ifPresent(
            concurrencyLimit -> update(dslContext, concurrencyLimit.withRunning(concurrencyLimit.getRunning() + 1))
        );
    }

    /**
     * 按租户读取全部并发计数状态。
     *
     * @param tenantId 租户标识；null 表示无租户记录
     * @return 匹配的状态列表；无记录时为空
     */
    public List<ConcurrencyLimit> find(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(this.buildTenantCondition(tenantId));

                return this.jdbcRepository.fetch(select);
            });
    }

    /**
     * 保存给定并发计数状态；调用方负责并发安全，带 DSLContext 的重载复用该事务。
     *
     * @param concurrencyLimit 需要保存的并发计数状态；不能为 null
     * @return 保存的状态对象，与输入共享实例
     */
    public ConcurrencyLimit update(ConcurrencyLimit concurrencyLimit) {
        Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(concurrencyLimit);
        this.jdbcRepository.persist(concurrencyLimit, fields);

        return concurrencyLimit;
    }

    /**
     * 按流程唯一键读取并锁定并发计数，锁保持到当前事务结束。
     *
     * @param dslContext 当前事务使用的 DSLContext；不能为 null
     * @param flow 携带租户、命名空间和标识的流程；不能为 null
     * @return 匹配状态；未找到时为空
     */
    private Optional<ConcurrencyLimit> fetchOne(DSLContext dslContext, FlowInterface flow) {
        var select = dslContext
            .select()
            .from(table())
            .where(table().KEY.eq(IdUtils.fromPartsAndSeparator('|', flow.getTenantId(), flow.getNamespace(), flow.getId())));

        return this.jdbcRepository.fetchOne(select.forUpdate());
    }

    /**
     * 保存给定并发计数状态；调用方负责并发安全，带 DSLContext 的重载复用该事务。
     *
     * @param dslContext 当前事务使用的 DSLContext；不能为 null
     * @param concurrencyLimit 需要保存的并发计数状态；不能为 null
     */
    private void update(DSLContext dslContext, ConcurrencyLimit concurrencyLimit) {
        Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(concurrencyLimit);
        this.jdbcRepository.persist(concurrencyLimit, dslContext, fields);
    }

    /**
     * 按租户、命名空间和流程标识读取并发计数。
     *
     * @param tenantId 租户标识；null 表示无租户记录
     * @param namespace 需要匹配的命名空间；不能为 null
     * @param flowId 需要匹配的流程标识；不能为 null
     * @return 匹配状态；未找到时为空
     */
    public Optional<ConcurrencyLimit> findById(String tenantId, String namespace, String flowId) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(this.buildTenantCondition(tenantId))
                    .and(table().NAMESPACE.eq(namespace))
                    .and(table().FLOW_ID.eq(flowId));
                return this.jdbcRepository.fetchOne(select);
            });
    }
}
