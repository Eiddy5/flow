package org.flow.queue.jdbc;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.services.IgnoreExecutionService;
import org.flow.core.utils.ExecutorsUtils;
import org.flow.queue.QueueService;
import org.flow.queue.jdbc.client.JdbcQueueClient;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@JdbcQueueEnabled
@Singleton
public record JdbcDependencies(@Inject QueueService queueService,
    @Inject JdbcQueueClient jdbcQueueClient,
    @Inject ExecutorsUtils executorsUtils,
    @Inject MetricRegistry metricRegistry,
    @Inject IgnoreExecutionService ignoreExecutionService) {
}
