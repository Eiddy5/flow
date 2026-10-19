package org.flow.queue.jdbc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.queues.QueueException;
import org.flow.core.queues.QueueSubscriber;
import org.flow.core.queues.event.DispatchEvent;
import org.flow.core.services.IgnoreExecutionService;
import org.flow.core.utils.ExecutorsUtils;
import org.flow.queue.AbstractDispatchQueue;
import org.flow.queue.QueueRecord;
import org.flow.queue.QueueService;
import org.flow.queue.jdbc.client.JdbcDispatchSubscriber;
import org.flow.queue.jdbc.client.JdbcQueueClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JdbcDispatchQueue<T extends DispatchEvent> extends AbstractDispatchQueue<T> {
    private final JdbcQueueClient jdbcQueueClient;
    private final MetricRegistry metricRegistry;
    private final IgnoreExecutionService ignoreExecutionService;

    public JdbcDispatchQueue(Class<T> cls, QueueService queueService, JdbcQueueClient jdbcQueueClient, ExecutorsUtils executorsUtils, MetricRegistry metricRegistry,
        IgnoreExecutionService ignoreExecutionService) {
        super(cls, queueService, executorsUtils, metricRegistry);

        this.jdbcQueueClient = jdbcQueueClient;
        this.metricRegistry = metricRegistry;
        this.ignoreExecutionService = ignoreExecutionService;
    }

    @Override
    protected QueueSubscriber<T> doSubscriber() {
        return new JdbcDispatchSubscriber<>(
            cls,
            queueService,
            jdbcQueueClient,
            queueName(),
            null,
            metricRegistry,
            ignoreExecutionService
        );
    }

    @Override
    protected void doEmit(byte[] message, String key) throws QueueException {
        jdbcQueueClient.publish(this.queueName(), null, key, new String(message, StandardCharsets.UTF_8));
    }

    @Override
    protected void doEmit(List<QueueRecord> messages) throws QueueException {
        String queueName = this.queueName();
        jdbcQueueClient.publish(
            messages
                .stream()
                .map(e -> new JdbcQueueClient.PublishedMessage(queueName, null, e.key(), new String(e.value(), StandardCharsets.UTF_8)))
                .toList()
        );
    }

}
