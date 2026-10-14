package org.flow.queue.jdbc;

import org.flow.core.utils.Either;
import org.flow.core.exceptions.DeserializationException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiPredicate;

import org.flow.core.runners.TransactionContext;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.queues.QueueException;
import org.flow.core.queues.QueueSubscriber;
import org.flow.core.queues.event.KeyedDispatchEvent;
import org.flow.core.services.IgnoreExecutionService;
import org.flow.core.utils.ExecutorsUtils;
import org.flow.queue.AbstractKeyedDispatchQueue;
import org.flow.queue.QueueRecord;
import org.flow.queue.QueueService;
import org.flow.queue.jdbc.client.JdbcDispatchSubscriber;
import org.flow.queue.jdbc.client.JdbcQueueClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JdbcKeyedDispatchQueue<T extends KeyedDispatchEvent> extends AbstractKeyedDispatchQueue<T> {
    private final JdbcQueueClient jdbcQueueClient;
    private final MetricRegistry metricRegistry;
    private final IgnoreExecutionService ignoreExecutionService;

    public JdbcKeyedDispatchQueue(Class<T> cls, QueueService queueService, JdbcQueueClient JdbcQueueClient, ExecutorsUtils executorsUtils, MetricRegistry metricRegistry,
        IgnoreExecutionService ignoreExecutionService) {
        super(cls, queueService, executorsUtils, metricRegistry);

        this.jdbcQueueClient = JdbcQueueClient;
        this.metricRegistry = metricRegistry;
        this.ignoreExecutionService = ignoreExecutionService;
    }

    @Override
    protected QueueSubscriber<T> doSubscriber(String routingKey) {
        return new JdbcDispatchSubscriber<>(
            cls,
            queueService,
            jdbcQueueClient,
            queueName(),
            routingKey == null ? List.of() : List.of(routingKey),
            metricRegistry,
            ignoreExecutionService
        );
    }

    @Override
    public Integer queueLag(String routingKey) {
        return this.jdbcQueueClient.queueLag(queueName(), routingKey);
    }

    @Override
    public List<T> claim(String routingKey, int limit, BiPredicate<TransactionContext, Either<T, DeserializationException>> prepare) {
        return jdbcQueueClient.claimDispatch(queueName(), routingKey, limit, (transaction, bytes) -> {
            var decoded = queueService.deserialize(cls, bytes);
            if (decoded.isLeft() && ignoreExecutionService.ignoreQueueRecord(decoded.getLeft().key())) {
                return null;
            }
            boolean accepted = prepare.test(transaction, decoded);
            return accepted && decoded.isLeft() ? decoded.getLeft() : null;
        });
    }

    @Override
    protected void doEmit(String routingKey, byte[] message, String key) throws QueueException {
        jdbcQueueClient.publish(this.queueName(), routingKey, key, new String(message, StandardCharsets.UTF_8));
    }

    @Override
    protected void doEmit(String routingKey, List<QueueRecord> messages) throws QueueException {
        String queueName = this.queueName();
        jdbcQueueClient.publish(
            messages
                .stream()
                .map(e -> new JdbcQueueClient.PublishedMessage(queueName, routingKey, e.key(), new String(e.value(), StandardCharsets.UTF_8)))
                .toList()
        );
    }
}
