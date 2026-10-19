package org.flow.queue.jdbc.client;

import java.util.List;
import java.util.function.Consumer;

import org.flow.core.exceptions.DeserializationException;
import org.flow.core.metrics.MetricRegistry;
import org.flow.core.queues.QueueSubscriber;
import org.flow.core.queues.event.Event;
import org.flow.core.services.IgnoreExecutionService;
import org.flow.core.utils.Either;
import org.flow.queue.AbstractPollingSubscriber;
import org.flow.queue.QueueService;
import org.flow.queue.poller.QueuePollerConfiguration;

public abstract class JdbcSubscriber<T extends Event> extends AbstractPollingSubscriber<T> {
    protected final JdbcQueueClient jdbcQueueClient;
    protected final String queueName;

    public JdbcSubscriber(
        Class<T> cls,
        QueueService queueService,
        JdbcQueueClient jdbcQueueClient,
        String queueName,
        MetricRegistry metricRegistry,
        IgnoreExecutionService ignoreExecutionService) {
        super(
            cls, queueName, queueService, metricRegistry, ignoreExecutionService, new QueuePollerConfiguration(
                jdbcQueueClient.getConfiguration().minPollInterval(),
                jdbcQueueClient.getConfiguration().maxPollInterval(),
                jdbcQueueClient.getConfiguration().pollSwitchInterval(),
                jdbcQueueClient.getConfiguration().pollSize(),
                jdbcQueueClient.getConfiguration().switchSteps(),
                jdbcQueueClient.getConfiguration().immediateRepoll()
            )
        );

        this.jdbcQueueClient = jdbcQueueClient;
        this.queueName = queueName;
    }

    protected abstract void init();

    @Override
    public QueueSubscriber<T> subscribe(Consumer<Either<T, DeserializationException>> consumer) {
        this.init();

        return super.subscribe(consumer);
    }

    @Override
    public QueueSubscriber<T> subscribeBatch(Consumer<List<Either<T, DeserializationException>>> consumer) {
        this.init();

        return super.subscribeBatch(consumer);
    }
}
