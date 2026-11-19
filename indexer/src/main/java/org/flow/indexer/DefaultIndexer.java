package org.flow.indexer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.queues.*;
import org.flow.core.queues.event.DispatchEvent;
import org.flow.core.repositories.LogDataStoreInterface;
import org.flow.core.runners.Indexer;
import org.flow.core.runners.IndexingRepository;
import org.flow.core.server.AbstractService;
import org.flow.core.server.ServiceStateChangeEvent;
import org.flow.core.server.ServiceType;
import org.flow.core.services.IgnoreExecutionService;
import org.flow.core.utils.ListUtils;

import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * This class is responsible for batch-indexing asynchronously queue messages.
 * <p>
 */
@SuppressWarnings("this-escape")
@Slf4j
@Singleton
public class DefaultIndexer extends AbstractService implements Indexer {
    private final LogDataStoreInterface logRepository;
    private final DispatchQueueInterface<LogEntry> logQueue;



    private final MetricRegistry metricRegistry;
    // Thread-safe: populated by run() but iterated from doStop() on the context-close thread.
    private final List<QueueSubscriber<?>> subscribers = new CopyOnWriteArrayList<>();

    private final IgnoreExecutionService ignoreExecutionService;
    private final QueueService queueService;

    @Inject
    public DefaultIndexer(
        LogDataStoreInterface logRepository,
        DispatchQueueInterface<LogEntry> logQueue,
        MetricRegistry metricRegistry,
        ApplicationEventPublisher<ServiceStateChangeEvent> eventPublisher,
        IgnoreExecutionService ignoreExecutionService,
        QueueService queueService) {
        super(ServiceType.INDEXER, eventPublisher);

        this.logRepository = logRepository;
        this.logQueue = logQueue;
        this.metricRegistry = metricRegistry;
        this.ignoreExecutionService = ignoreExecutionService;
        this.queueService = queueService;

        setState(ServiceState.CREATED);
    }

    @Override
    public void run() {
        guardedStart(() ->
        {
            log.debug("Starting the indexer");
            startQueues();
        }, () ->
        {
            setState(ServiceState.RUNNING);
            log.info("Indexer started");
        });
    }

    protected void startQueues() {
        this.sendBatch(logQueue, logRepository);
    }

    protected <T extends DispatchEvent> void sendBatch(DispatchQueueInterface<T> queueInterface, IndexingRepository<T> indexingRepository) {
        this.subscribers.addFirst(queueInterface.subscriber().subscribeBatch(eithers ->
        {
            // first, log all deserialization issues
            eithers.stream().filter(either -> either.isRight()).forEach(either -> log.error("unable to deserialize an item: {}", either.getRight().getMessage()));

            // then index all correctly deserialized items
            List<T> items = eithers.stream()
                .filter(either -> either.isLeft())
                .map(either -> either.getLeft())
                .filter(it ->
                {
                    if (ignoreExecutionService.ignoreIndexerRecord(queueService.key(it))) {
                        log.warn("Skipping indexer record for key: {}", queueService.key(it));
                        return false;
                    }
                    return true;
                })
                .toList();

            if (!ListUtils.isEmpty(items)) {
                String itemClassName = items.getFirst().getClass().getName();
                this.metricRegistry.counter(MetricRegistry.METRIC_INDEXER_REQUEST_COUNT, MetricRegistry.METRIC_INDEXER_REQUEST_COUNT_DESCRIPTION, "type", itemClassName).increment();
                this.metricRegistry.counter(MetricRegistry.METRIC_INDEXER_MESSAGE_IN_COUNT, MetricRegistry.METRIC_INDEXER_MESSAGE_IN_COUNT_DESCRIPTION, "type", itemClassName)
                    .increment(items.size());

                this.metricRegistry.timer(MetricRegistry.METRIC_INDEXER_REQUEST_DURATION, MetricRegistry.METRIC_INDEXER_REQUEST_DURATION_DESCRIPTION, "type", itemClassName).record(() ->
                {
                    int saved = indexingRepository.saveBatch(items);
                    this.metricRegistry.counter(MetricRegistry.METRIC_INDEXER_MESSAGE_OUT_COUNT, MetricRegistry.METRIC_INDEXER_MESSAGE_OUT_COUNT_DESCRIPTION, "type", itemClassName)
                        .increment(saved);
                });
            }
        }));
    }

    @Override
    protected ServiceState doStop() {
        this.subscribers.forEach(QueueSubscriber::close);
        return ServiceState.TERMINATED_GRACEFULLY;
    }
}
