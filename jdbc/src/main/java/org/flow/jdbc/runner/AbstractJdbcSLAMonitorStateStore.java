package org.flow.jdbc.runner;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Consumer;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.SlaMonitorTable;
import org.flow.core.models.flows.sla.SLAMonitor;
import org.flow.executor.SLAMonitorStateStore;
import org.flow.jdbc.repository.AbstractJdbcRepository;

public abstract class AbstractJdbcSLAMonitorStateStore extends AbstractJdbcRepository implements SLAMonitorStateStore {
    protected org.flow.jdbc.AbstractJdbcRepository<SLAMonitor> jdbcRepository;

    /**
     * 返回当前存储使用的生成表，保留配置的物理名称和字段类型。
     *
     * @return 与当前 JDBC Repository 绑定的生成表
     */
    @Override
    protected SlaMonitorTable table() {
        return jdbcRepository.getTable(Tables.SLA_MONITOR);
    }

    protected AbstractJdbcSLAMonitorStateStore(org.flow.jdbc.AbstractJdbcRepository<SLAMonitor> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    /**
     * 在当前数据源事务中保存 SLA 监控状态。
     *
     * @param slaMonitor 待保存的 SLA 监控状态；不能为 null
     */
    @Override
    public void save(SLAMonitor slaMonitor) {
        this.jdbcRepository
            .getDslContextWrapper()
            .transaction(configuration ->
            {
                DSLContext context = DSL.using(configuration);
                Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(slaMonitor);
                this.jdbcRepository.persist(slaMonitor, context, fields);
            });
    }

    /**
     * 删除给定执行标识对应的全部 SLA 监控状态。
     *
     * @param executionId 需要删除监控状态的执行标识；不能为 null
     */
    @Override
    public void purge(String executionId) {
        this.jdbcRepository
            .getDslContextWrapper()
            .transaction(configuration ->
            {
                DSLContext context = DSL.using(configuration);
                context.delete(table())
                    .where(table().EXECUTION_ID.eq(executionId))
                    .execute();
            });
    }

    /**
     * 锁定截止时间前到期的 SLA 监控，跳过已锁记录；回调成功后删除对应状态。
     *
     * @param date 到期判断的截止时间点；不能为 null
     * @param consumer 处理查询结果的回调；不能为 null，异常会向调用方传播
     */
    @Override
    public void processExpired(Instant date, Consumer<SLAMonitor> consumer) {
        this.jdbcRepository
            .getDslContextWrapper()
            .transaction(configuration ->
            {
                DSLContext context = DSL.using(configuration);
                var select = context.select()
                    .from(table())
                    .where(table().DEADLINE.lt(date.atOffset(ZoneOffset.UTC)))
                    .forUpdate()
                    .skipLocked();

                this.jdbcRepository.fetch(select)
                    .forEach(slaMonitor ->
                    {
                        consumer.accept(slaMonitor);
                        jdbcRepository.delete(slaMonitor);
                    });
            });
    }
}
