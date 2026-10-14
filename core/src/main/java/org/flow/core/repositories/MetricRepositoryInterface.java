package org.flow.core.repositories;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Function;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.MetricEntry;
import org.flow.core.models.executions.metrics.MetricAggregations;
import org.flow.core.runners.IndexingRepository;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Pageable;
import reactor.core.publisher.Flux;

public interface MetricRepositoryInterface extends IndexingRepository<MetricEntry> {
    ArrayListTotal<MetricEntry> findByExecutionId(String tenantId, String id, Pageable pageable);

    ArrayListTotal<MetricEntry> findByExecutionIdAndTaskId(String tenantId, String executionId, String taskId, Pageable pageable);

    ArrayListTotal<MetricEntry> findByExecutionIdAndTaskRunId(String tenantId, String executionId, String taskRunId, Pageable pageable);

    List<String> flowMetrics(String tenantId, String namespace, String flowId);

    List<String> taskMetrics(String tenantId, String namespace, String flowId, String taskId);

    List<String> tasksWithMetrics(String tenantId, String namespace, String flowId);

    MetricAggregations aggregateByFlowId(String tenantId, String namespace, String flowId, @Nullable String taskId, String metric, ZonedDateTime startDate, ZonedDateTime endDate,
        String aggregation);

    Integer purge(Execution execution);

    Integer purge(List<Execution> executions);

    Flux<MetricEntry> findAllAsync(@Nullable String tenantId);

    default Function<String, String> sortMapping() throws IllegalArgumentException {
        return s -> s;
    }
}
