package org.flow.executor.handler;

import java.util.Optional;

import org.flow.core.async.AsyncOperationProcessedEvent;
import org.flow.core.async.AsyncOperationService;
import org.flow.core.killswitch.EvaluationType;
import org.flow.core.killswitch.KillSwitchService;
import org.flow.core.metrics.MetricRegistry;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionKilled;
import org.flow.core.models.executions.ExecutionKilledExecution;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.flows.State;
import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.FlowMetaStoreInterface;
import org.flow.core.services.ExecutionService;
import org.flow.executor.ExecutionStateStore;
import org.flow.executor.ExecutorContext;
import org.flow.executor.ExecutorMessageHandler;
import org.flow.executor.ExecutorService;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class ExecutionKilledExecutionMessageHandler implements ExecutorMessageHandler<ExecutionKilledExecution> {
    private final ExecutorService executorService;
    private final ExecutionService executionService;

    private final ExecutionStateStore executionStateStore;

    private final MetricRegistry metricRegistry;

    private final FlowMetaStoreInterface flowMetaStore;

    private final BroadcastQueueInterface<ExecutionKilled> killQueue;

    private final AsyncOperationService asyncOperationService;

    private final KillSwitchService killSwitchService;

    @Inject
    public ExecutionKilledExecutionMessageHandler(
        ExecutorService executorService,
        ExecutionService executionService,
        ExecutionStateStore executionStateStore,
        MetricRegistry metricRegistry,
        FlowMetaStoreInterface flowMetaStore,
        BroadcastQueueInterface<ExecutionKilled> killQueue,
        AsyncOperationService asyncOperationService,
        KillSwitchService killSwitchService) {
        this.executorService = executorService;
        this.executionService = executionService;
        this.executionStateStore = executionStateStore;
        this.metricRegistry = metricRegistry;
        this.flowMetaStore = flowMetaStore;
        this.killQueue = killQueue;
        this.asyncOperationService = asyncOperationService;
        this.killSwitchService = killSwitchService;
    }

    @Override
    public Optional<ExecutorContext> handle(ExecutionKilledExecution message) {
        // Only IGNORE is filtered here — KILL and CANCEL still need to process the kill event.
        if (killSwitchService.evaluate(message.getExecutionId()) == EvaluationType.IGNORE) {
            log.warn("Ignoring execution {} because there is a kill switch on it", message.getExecutionId());
            return Optional.empty();
        }
        AsyncOperationProcessedEvent.Outcome outcome = AsyncOperationProcessedEvent.Outcome.SUCCEEDED;
        String error = null;
        try {
            return doHandle(message);
        } catch (RuntimeException e) {
            outcome = AsyncOperationProcessedEvent.Outcome.FAILED;
            error = e.getMessage();
            throw e;
        } finally {
            asyncOperationService.emitProcessedIfAsync(message, message.getTenantId(), message.getExecutionId(), outcome, error);
        }
    }

    private Optional<ExecutorContext> doHandle(ExecutionKilledExecution message) {
        metricRegistry
            .counter(MetricRegistry.METRIC_EXECUTOR_KILLED_COUNT, MetricRegistry.METRIC_EXECUTOR_KILLED_COUNT_DESCRIPTION, metricRegistry.tags(message))
            .increment();

        if (log.isDebugEnabled()) {
            executorService.log(log, true, message);
        }

        // Immediately fire the event in EXECUTED state to notify the Workers to kill
        // any remaining tasks for that executing regardless of if the execution exist or not.
        // Note, that this event will be a noop if all tasks for that execution are already killed or completed.
        try {
            killQueue.emit(
                ExecutionKilledExecution
                    .builder()
                    .executionId(message.getExecutionId())
                    .isOnKillCascade(false)
                    .state(ExecutionKilled.State.EXECUTED)
                    .tenantId(message.getTenantId())
                    .build()
            );
        } catch (QueueException e) {
            log.error("Unable to kill the execution {}", message.getExecutionId(), e);
        }

        Optional<ExecutorContext> maybeExecutor = killingOrAfterKillState(message.getExecutionId(), Optional.ofNullable(message.getExecutionState()));

        // Check whether kill event should be propagated to downstream executions.
        // By default, always propagate the ExecutionKill to sub-flows (for backward compatibility).
        Boolean isOnKillCascade = Optional.ofNullable(message.getIsOnKillCascade()).orElse(true);
        if (isOnKillCascade) {
            executionService
                .killSubflowExecutions(message.getTenantId(), message.getExecutionId())
                .doOnNext(executionKilled ->
                {
                    try {
                        killQueue.emit(executionKilled);
                    } catch (QueueException e) {
                        log.error("Unable to kill the execution {}", executionKilled.getExecutionId(), e);
                    }
                })
                .blockLast();

            // Also kill loop sub-executions created by a Loop task within this execution.
            for (ExecutionKilledExecution executionKilled : executionService.killLoopSubExecutions(message.getTenantId(), message.getExecutionId())) {
                try {
                    killQueue.emit(executionKilled);
                } catch (QueueException e) {
                    log.error("Unable to kill the loop sub-execution {}", executionKilled.getExecutionId(), e);
                }
            }
        }

        return maybeExecutor;
    }

    private Optional<ExecutorContext> killingOrAfterKillState(final String executionId, Optional<State.Type> afterKillState) {
        return executionStateStore.lock(executionId, execution ->
        {
            FlowInterface flow = flowMetaStore.findByExecution(execution).orElseThrow();

            Execution killing = executionService.kill(execution, flow, afterKillState);
            // kill() returns the same object unchanged when the execution is already terminal.
            // Calling withExecution() in that case would set executionUpdated=true and trigger
            // a spurious toExecution() cycle that re-emits SubflowExecutionEnd for subflows.
            if (killing == execution) {
                return null;
            }
            return new ExecutorContext(execution)
                .withExecution(killing, "joinKillingExecution");
        });
    }
}
