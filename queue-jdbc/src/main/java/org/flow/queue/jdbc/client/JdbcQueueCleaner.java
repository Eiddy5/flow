package org.flow.queue.jdbc.client;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;

import org.flow.core.annotations.RequiresExecutor;
import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.jdbc.JdbcTableConfig;
import org.flow.jdbc.JooqDSLContextWrapper;
import org.flow.jdbc.repository.AbstractJdbcRepository;
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
    private static final Field<Object> CREATED_FIELD = AbstractJdbcRepository.field("created");
    private static final Field<Object> TYPE_FIELD = AbstractJdbcRepository.field("type");

    private final JooqDSLContextWrapper dslContextWrapper;
    private final Table<Record> queueTable;
    private final List<BroadcastQueueInterface<?>> broadcastQueues;
    private final Duration retention;

    @Inject
    public JdbcQueueCleaner(@Named("queues") JdbcTableConfig jdbcTableConfig,
        JooqDSLContextWrapper dslContextWrapper,
        List<BroadcastQueueInterface<?>> broadcastQueues,
        @Property(name = "flow.jdbc.queue.cleaner.retention", defaultValue = "1h") Duration retention) {
        this.dslContextWrapper = dslContextWrapper;
        this.broadcastQueues = broadcastQueues;
        this.retention = retention;

        this.queueTable = DSL.table(jdbcTableConfig.table());
    }

    @Scheduled(initialDelay = "${flow.jdbc.queue.cleaner.initial-delay:1h}", fixedDelay = "${flow.jdbc.queue.cleaner.fixed-delay:1h}")
    public long deleteQueue() {
        LongAdder totalDeleted = new LongAdder();
        broadcastQueues.forEach(queue -> dslContextWrapper.transaction(configuration ->
        {
            var condition = CREATED_FIELD.lessOrEqual(period(retention)).and(TYPE_FIELD.eq(queue.queueName()));
            int deleted = delete(configuration, condition);
            log.info("Cleaned {} records for the '{}' queue", deleted, queue.queueName());
            totalDeleted.add(deleted);
        }));

        return totalDeleted.longValue();
    }

    private int delete(org.jooq.Configuration configuration, Condition condition) {
        return DSL.using(configuration).delete(this.queueTable).where(condition).execute();
    }

    private Temporal period(Duration retention) {
        return ZonedDateTime.now().minus(retention).toOffsetDateTime();
    }

}
