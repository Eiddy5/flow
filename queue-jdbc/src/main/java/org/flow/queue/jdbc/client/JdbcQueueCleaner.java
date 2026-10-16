package org.flow.queue.jdbc.client;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import org.jooq.Condition;
import org.jooq.impl.DSL;

import static org.flow.gen.flow.Tables.QUEUES;

import org.flow.core.annotations.RequiresExecutor;
import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.gen.flow.tables.QueuesTable;
import org.flow.jdbc.AbstractJdbcRepository;
import org.flow.jdbc.JdbcQueueItem;
import org.flow.jdbc.JooqDSLContextWrapper;
import org.flow.jdbc.runner.JdbcQueueEnabled;

import io.micronaut.context.annotation.Property;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@JdbcQueueEnabled
@RequiresExecutor
@Slf4j
public class JdbcQueueCleaner {

    private final JooqDSLContextWrapper dslContextWrapper;
    private final QueuesTable queueTable;
    private final List<BroadcastQueueInterface<?>> broadcastQueues;
    private final Duration retention;

    /**
     * 使用生成队列表创建清理器，定时删除配置保留时长之前的广播消息。
     *
     * @param jdbcRepository 当前存储绑定的 JDBC Repository；不能为 null
     * @param dslContextWrapper 负责具名数据源和事务的包装器；不能为 null
     * @param broadcastQueues 需要清理的广播队列；不能为 null
     * @param retention 广播消息保留时长；不能为 null
     */
    @Inject
    public JdbcQueueCleaner(@Named("queues") AbstractJdbcRepository<JdbcQueueItem> jdbcRepository,
        JooqDSLContextWrapper dslContextWrapper,
        List<BroadcastQueueInterface<?>> broadcastQueues,
        @Property(name = "flow.jdbc.queue.cleaner.retention", defaultValue = "1h") Duration retention) {
        this.dslContextWrapper = dslContextWrapper;
        this.broadcastQueues = broadcastQueues;
        this.retention = retention;

        this.queueTable = jdbcRepository.getTable(QUEUES);
    }

    /**
     * 按广播队列逐个开启事务，删除超过保留时长的消息。
     *
     * @return 本轮删除的消息总数
     */
    @Scheduled(initialDelay = "${flow.jdbc.queue.cleaner.initial-delay:1h}", fixedDelay = "${flow.jdbc.queue.cleaner.fixed-delay:1h}")
    public long deleteQueue() {
        LongAdder totalDeleted = new LongAdder();
        broadcastQueues.forEach(queue -> dslContextWrapper.transaction(configuration ->
        {
            var condition = queueTable.CREATED.lessOrEqual(period(retention)).and(queueTable.TYPE.eq(queue.queueName()));
            int deleted = delete(configuration, condition);
            log.info("Cleaned {} records for the '{}' queue", deleted, queue.queueName());
            totalDeleted.add(deleted);
        }));

        return totalDeleted.longValue();
    }

    /**
     * 使用给定事务配置和条件删除队列表中的消息。
     *
     * @param configuration 删除操作使用的 jOOQ 事务配置；不能为 null
     * @param condition 限制待处理记录的 SQL 条件；不能为 null
     * @return 删除的记录数
     */
    private int delete(org.jooq.Configuration configuration, Condition condition) {
        return DSL.using(configuration).delete(this.queueTable).where(condition).execute();
    }

    /**
     * 计算当前时间减去保留时长后的清理截止时间。
     *
     * @param retention 广播消息保留时长；不能为 null
     * @return 带时区的截止时间点
     */
    private OffsetDateTime period(Duration retention) {
        return ZonedDateTime.now().minus(retention).toOffsetDateTime();
    }

}
