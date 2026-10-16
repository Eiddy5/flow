package org.flow.queue.jdbc.client;

import java.util.ArrayList;
import java.util.function.BiFunction;
import org.flow.core.runners.TransactionContext;
import org.flow.jdbc.runner.JdbcTransactionContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import org.apache.commons.lang3.tuple.Pair;
import org.jooq.DSLContext;
import org.jooq.exception.DataException;
import org.jooq.impl.DSL;

import org.flow.core.queues.QueueException;
import org.flow.core.queues.UnsupportedMessageException;
import org.flow.gen.flow.tables.QueuesTable;
import org.flow.jdbc.AbstractJdbcRepository;
import org.flow.jdbc.JdbcJsonbUtils;
import org.flow.jdbc.JdbcQueueItem;
import org.flow.jdbc.JooqDSLContextWrapper;
import org.flow.jdbc.runner.JdbcQueueConfiguration;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.Getter;

import static org.flow.gen.flow.Tables.QUEUES;

@Singleton
public class JdbcQueueClient {
    private final QueuesTable table;

    private final JooqDSLContextWrapper dslContextWrapper;

    @Getter
    private final JdbcQueueConfiguration configuration;

    /**
     * 使用指定队列 Repository 的生成表创建客户端，保留其物理表配置。
     *
     * @param jdbcRepository 当前存储绑定的 JDBC Repository；不能为 null
     * @param dslContextWrapper 负责具名数据源和事务的包装器；不能为 null
     * @param configuration 队列轮询配置；不能为 null
     */
    @Inject
    public JdbcQueueClient(@Named("queues") AbstractJdbcRepository<JdbcQueueItem> jdbcRepository, JooqDSLContextWrapper dslContextWrapper, JdbcQueueConfiguration configuration) {
        this.table = jdbcRepository.getTable(QUEUES);
        this.dslContextWrapper = dslContextWrapper;
        this.configuration = configuration;
    }

    private boolean isUnsupportedUnicode(DataException e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (
                    message.contains("unsupported Unicode escape sequence") ||
                        lower.contains("surrogate") ||
                        lower.contains("unicode escape") ||
                        lower.contains("invalid unicode")
                ) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * 在一个事务中写入给定消息，JSONB 保留现有载荷协议；无效 Unicode 数据转换为队列异常。
     *
     * @param queue 消息所属的队列名称；不能为 null
     * @param routingKey 路由键；null 或空字符串表示无路由键
     * @param key 需要定位的记录或消息键；不能为 null
     * @param value 待持久化的 JSON 文本；不能为 null
     * @throws QueueException 当数据库拒绝消息数据时抛出
     */
    public void publish(String queue, @Nullable String routingKey, String key, String value) throws QueueException {
        try {
            dslContextWrapper.transaction(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                var insert = context.insertInto(table)
                    .set(table.TYPE, queue)
                    .set(table.ROUTING_KEY, (routingKey == null || routingKey.isEmpty()) ? null : routingKey)
                    .set(table.KEY, key)
                    .set(table.VALUE, JdbcJsonbUtils.valueOf(value))
                    .set(table.CREATED, Instant.now().atOffset(ZoneOffset.UTC));

                insert.execute();
            });
        } catch (DataException e) { // The exception is from the data itself, not the database/network/driver so instead of fail fast, we throw a recoverable QueueException
            // Postgres refuses JSONB payloads with unsupported Unicode escape sequences such as '\0000'
            // or lone UTF-16 surrogates. Convert those into a recoverable queue error.
            // We try to detect that and fail with a specific exception so the Worker can recover from it.
            if (isUnsupportedUnicode(e)) {
                throw new UnsupportedMessageException(e.getMessage(), e);
            }
            throw new QueueException("Unable to emit a message to the queue", e);
        }
    }

    /**
     * 统计给定队列和路由键下尚未删除的消息数。
     *
     * @param queue 消息所属的队列名称；不能为 null
     * @param routingKey 路由键；null 或空字符串表示无路由键
     * @return 匹配的消息数量
     */
    public Integer queueLag(String queue, @Nullable String routingKey) {
        return dslContextWrapper.transactionResult(configuration ->
        {
            DSLContext ctx = DSL.using(configuration);

            var condition = table.TYPE.eq(queue);
            if (routingKey != null && !routingKey.isEmpty()) {
                condition = condition.and(table.ROUTING_KEY.eq(routingKey));
            } else {
                condition = condition.and(table.ROUTING_KEY.isNull());
            }

            return ctx.selectCount()
                .from(table)
                .where(condition)
                .fetchOneInto(Integer.class);
        });
    }

    public record PublishedMessage(String queue, String routingKey, String key, String value) {
    }

    /**
     * 在一个事务中写入给定消息，JSONB 保留现有载荷协议；无效 Unicode 数据转换为队列异常。
     *
     * @param messages 按列表顺序写入的消息；不能为 null，列表不会被修改
     * @throws QueueException 当数据库拒绝消息数据时抛出
     */
    public void publish(List<PublishedMessage> messages) throws QueueException {
        try {
            dslContextWrapper.transaction(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                var insert = context.insertInto(table,
                    table.TYPE, table.ROUTING_KEY, table.KEY, table.VALUE, table.CREATED);

                var now = Instant.now().atOffset(ZoneOffset.UTC);
                for (PublishedMessage entry : messages) {
                    insert = insert.values(
                        entry.queue,
                        (entry.routingKey == null || entry.routingKey.isEmpty()) ? null : entry.routingKey,
                        entry.key,
                        JdbcJsonbUtils.valueOf(entry.value),
                        now
                    );
                }

                insert.execute();
            });
        } catch (DataException e) { // The exception is from the data itself, not the database/network/driver so instead of fail fast, we throw a recoverable QueueException
            // Postgres refuses JSONB payloads with unsupported Unicode escape sequences such as '\0000'
            // or lone UTF-16 surrogates. Convert those into a recoverable queue error.
            // We try to detect that and fail with a specific exception so the Worker can recover from it.
            if (isUnsupportedUnicode(e)) {
                throw new UnsupportedMessageException(e.getMessage(), e);
            }
            throw new QueueException("Unable to emit a message to the queue", e);
        }
    }

    /**
     * 按偏移量锁定消息并跳过已锁记录，先在同一事务执行准备回调，再删除已领取的消息。
     *
     * @param <T> 准备回调返回的消息类型
     * @param queue 消息所属的队列名称；不能为 null
     * @param routingKey 路由键；null 或空字符串表示无路由键
     * @param limit 本次最多领取的消息数；小于 1 时不查询
     * @param prepare 在领取事务内登记消息的回调；返回 null 时不加入结果列表
     * @return 准备回调返回的非 null 结果，按领取顺序排列
     */
    public <T> List<T> claimDispatch(String queue, String routingKey, int limit,
        BiFunction<TransactionContext, byte[], T> prepare) {
        if (limit < 1) {
            return List.of();
        }
        return dslContextWrapper.transactionResult(conf -> {
            DSLContext context = DSL.using(conf);
            var records = context.select(table.OFFSET, table.VALUE).from(table)
                .where(table.TYPE.eq(queue))
                .and(routingKey == null || routingKey.isEmpty() ? table.ROUTING_KEY.isNull() : table.ROUTING_KEY.eq(routingKey))
                .orderBy(table.OFFSET.asc()).limit(Math.min(limit, configuration.pollSize()))
                .forUpdate().skipLocked().fetch();
            var transaction = new JdbcTransactionContext(context);
            List<T> accepted = new ArrayList<>();
            for (var record : records) {
                T message = prepare.apply(transaction, record.get(table.VALUE).data().getBytes(StandardCharsets.UTF_8));
                if (message != null) {
                    accepted.add(message);
                }
            }
            if (!records.isEmpty()) {
                context.delete(table).where(table.OFFSET.in(records.getValues(table.OFFSET))).execute();
            }
            return accepted;
        });
    }

    /**
     * 按偏移量锁定并逐条消费消息，跳过已锁记录；消费者成功后在同一事务删除消息。
     *
     * @param queue 消息所属的队列名称；不能为 null
     * @param routingKeys 需要消费的路由键集合；null 或空集合表示无路由键
     * @param consumer 处理查询结果的回调；不能为 null，异常会向调用方传播
     * @return 本次消费的消息数
     */
    public Integer subscribeDispatch(String queue, @Nullable List<String> routingKeys, Consumer<byte[]> consumer) {
        return dslContextWrapper.transactionResult(conf ->
        {
            DSLContext context = DSL.using(conf);

            var select = context.select(table.OFFSET, table.VALUE)
                .from(table)
                .where(table.TYPE.eq(queue));

            if (routingKeys != null && !routingKeys.isEmpty()) {
                select = select.and(table.ROUTING_KEY.in(routingKeys));
            } else {
                select = select.and(table.ROUTING_KEY.isNull());
            }

            var result = select
                .orderBy(table.OFFSET.asc())
                .limit(configuration.pollSize())
                .forUpdate()
                .skipLocked()
                .fetch();

            if (!result.isEmpty()) {
                List<Integer> processedItems = result
                    .stream()
                    .map(record ->
                    {
                        consumer.accept(record.get(table.VALUE).data().getBytes(StandardCharsets.UTF_8));
                        return record.get(table.OFFSET);
                    })
                    .toList();

                if (!processedItems.isEmpty()) {
                    var delete = context.delete(table)
                        .where(table.OFFSET.in(processedItems));

                    delete.execute();
                }
            }

            return result.size();
        });
    }

    /**
     * 按偏移量锁定消息后批量调用消费者，跳过已锁记录；消费者成功后在同一事务删除消息。
     *
     * @param queue 消息所属的队列名称；不能为 null
     * @param routingKeys 需要消费的路由键集合；null 或空集合表示无路由键
     * @param consumer 处理查询结果的回调；不能为 null，异常会向调用方传播
     * @return 本次消费的消息数
     */
    public Integer subscribeDispatchBatch(String queue, List<String> routingKeys, Consumer<List<byte[]>> consumer) {
        return dslContextWrapper.transactionResult(conf ->
        {
            DSLContext context = DSL.using(conf);

            var select = context.select(table.OFFSET, table.VALUE)
                .from(table)
                .where(table.TYPE.eq(queue));

            if (routingKeys != null && !routingKeys.isEmpty()) {
                select = select.and(table.ROUTING_KEY.in(routingKeys));
            } else {
                select = select.and(table.ROUTING_KEY.isNull());
            }

            var result = select
                .orderBy(table.OFFSET.asc())
                .limit(configuration.pollSize())
                .forUpdate()
                .skipLocked()
                .fetch();

            if (!result.isEmpty()) {
                consumer.accept(result.stream().map(record -> record.get(table.VALUE).data().getBytes(StandardCharsets.UTF_8)).toList());

                List<Integer> processedItems = result
                    .stream()
                    .map(record -> record.get(table.OFFSET))
                    .toList();

                var delete = context.delete(table)
                    .where(table.OFFSET.in(processedItems));
                delete.execute();
            }

            return result.size();
        });
    }

    /**
     * 读取给定广播队列中无路由键消息的最大偏移量，并转换为接口使用的 Long。
     *
     * @param queue 消息所属的队列名称；不能为 null
     * @return 最大偏移量；无消息时为 0
     */
    public @Nullable Long fetchMaxOffset(String queue) {
        Integer initialOffset = dslContextWrapper.transactionResult(conf ->
        {
            DSLContext context = DSL.using(conf);

            // Filters identically to subscribeBroadcast/subscribeBroadcastBatch so the seeded
            // offset and the poll queries always operate over the same row set.
            return context.select(DSL.max(table.OFFSET))
                .from(table)
                .where(table.TYPE.eq(queue))
                .and(table.ROUTING_KEY.isNull())
                .fetchOne(DSL.max(table.OFFSET));
        });

        return initialOffset != null ? initialOffset.longValue() : 0L;
    }

    /**
     * 按偏移量读取并逐条回调广播消息；消息保留在表内，返回本次处理后的游标。
     *
     * @param queue 消息所属的队列名称；不能为 null
     * @param maxOffset 上次消费的偏移量；null 表示从现存消息开始，非 null 值必须在 Integer 范围内
     * @param consumer 处理查询结果的回调；不能为 null，异常会向调用方传播
     * @return 消息数和最新偏移量；无新消息时沿用输入游标
     * @throws ArithmeticException 当输入偏移量超出数据库 Integer 范围时抛出
     */
    protected Pair<Integer, Long> subscribeBroadcast(String queue, @Nullable Long maxOffset, Consumer<byte[]> consumer) {
        return dslContextWrapper.transactionResult(conf ->
        {
            DSLContext context = DSL.using(conf);
            Long maxOffsetResult = null;

            // Broadcast messages are always published with a null routing key. Binding it explicitly here
            // (instead of leaving it unconstrained) lets the (type, routing_key, offset) index be used as a
            // seek on offset instead of a full scan of every retained row for this type on each poll.
            var select = context.select(table.OFFSET, table.VALUE)
                .from(table)
                .where(table.TYPE.eq(queue))
                .and(table.ROUTING_KEY.isNull());

            if (maxOffset != null) {
                select = select.and(table.OFFSET.gt(Math.toIntExact(maxOffset)));
            }

            var result = select
                .orderBy(table.OFFSET.asc())
                .limit(configuration.pollSize())
                .fetch();

            if (!result.isEmpty()) {
                result.forEach(record -> consumer.accept(record.get(table.VALUE).data().getBytes(StandardCharsets.UTF_8)));

                maxOffsetResult = result
                    .stream()
                    .map(record -> record.get(table.OFFSET).longValue())
                    .max(Long::compareTo)
                    .orElse(null);
            }

            return Pair.of(result.size(), maxOffsetResult != null ? maxOffsetResult : maxOffset);
        });
    }

    /**
     * 按偏移量读取并批量回调广播消息；消息保留在表内，返回本次处理后的游标。
     *
     * @param queue 消息所属的队列名称；不能为 null
     * @param maxOffset 上次消费的偏移量；null 表示从现存消息开始，非 null 值必须在 Integer 范围内
     * @param consumer 处理查询结果的回调；不能为 null，异常会向调用方传播
     * @return 消息数和最新偏移量；无新消息时沿用输入游标
     * @throws ArithmeticException 当输入偏移量超出数据库 Integer 范围时抛出
     */
    public Pair<Integer, Long> subscribeBroadcastBatch(String queue, Long maxOffset, Consumer<List<byte[]>> consumer) {
        return dslContextWrapper.transactionResult(conf ->
        {
            DSLContext context = DSL.using(conf);
            Long maxOffsetResult = null;

            // Broadcast messages are always published with a null routing key. Binding it explicitly here
            // (instead of leaving it unconstrained) lets the (type, routing_key, offset) index be used as a
            // seek on offset instead of a full scan of every retained row for this type on each poll.
            var select = context.select(table.OFFSET, table.VALUE)
                .from(table)
                .where(table.TYPE.eq(queue))
                .and(table.ROUTING_KEY.isNull());

            if (maxOffset != null) {
                select = select.and(table.OFFSET.gt(Math.toIntExact(maxOffset)));
            }

            var result = select
                .orderBy(table.OFFSET.asc())
                .limit(configuration.pollSize())
                .fetch();

            if (!result.isEmpty()) {
                consumer.accept(result.stream().map(record -> record.get(table.VALUE).data().getBytes(StandardCharsets.UTF_8)).toList());

                maxOffsetResult = result
                    .stream()
                    .map(record -> record.get(table.OFFSET).longValue())
                    .max(Long::compareTo)
                    .orElse(null);
            }

            return Pair.of(result.size(), maxOffsetResult != null ? maxOffsetResult : maxOffset);
        });
    }
}
