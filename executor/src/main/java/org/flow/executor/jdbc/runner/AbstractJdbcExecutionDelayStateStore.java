package org.flow.executor.jdbc.runner;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Consumer;

import org.jooq.Field;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.ExecutordelayedTable;
import org.flow.core.runners.ExecutionDelay;
import org.flow.executor.ExecutionDelayStateStore;
import org.flow.core.jdbc.repository.AbstractJdbcRepository;

public abstract class AbstractJdbcExecutionDelayStateStore extends AbstractJdbcRepository implements ExecutionDelayStateStore {
    protected org.flow.core.jdbc.AbstractJdbcRepository<ExecutionDelay> jdbcRepository;

    /**
     * 返回当前存储使用的生成表，保留配置的物理名称和字段类型。
     *
     * @return 与当前 JDBC Repository 绑定的生成表
     */
    @Override
    protected ExecutordelayedTable table() {
        return jdbcRepository.getTable(Tables.EXECUTORDELAYED);
    }

    public AbstractJdbcExecutionDelayStateStore(org.flow.core.jdbc.AbstractJdbcRepository<ExecutionDelay> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    /**
     * 锁定截至给定时间到期的延迟状态，跳过其他事务已锁记录；回调成功后删除对应状态。
     *
     * @param now 截至此时已到期的时间点；不能为 null
     * @param consumer 处理查询结果的回调；不能为 null，异常会向调用方传播
     */
    @Override
    public void processExpired(Instant now, Consumer<ExecutionDelay> consumer) {
        this.jdbcRepository
            .getDslContextWrapper()
            .transaction(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(table().DATE.lessOrEqual(now.atOffset(ZoneOffset.UTC)))
                    .forUpdate()
                    .skipLocked();

                this.jdbcRepository.fetch(select)
                    .forEach(executionDelay ->
                    {
                        consumer.accept(executionDelay);
                        jdbcRepository.delete(executionDelay);
                    });
            });
    }

    /**
     * 保存延迟执行的 JSON 状态，数据库派生列由现有存储协议维护。
     *
     * @param executionDelay 待保存的延迟执行状态；不能为 null
     */
    @Override
    public void save(ExecutionDelay executionDelay) {
        Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(executionDelay);
        this.jdbcRepository.persist(executionDelay, fields);
    }
}
