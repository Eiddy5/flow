package org.flow.executor.handler;

import java.util.Optional;

import org.flow.core.exceptions.FlowNotFoundException;
import org.flow.core.exceptions.InternalException;
import org.flow.core.killswitch.EvaluationType;
import org.flow.core.killswitch.KillSwitchService;
import org.flow.core.metrics.MetricRegistry;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.State;
import org.flow.core.runners.*;
import org.flow.core.services.ExecutionService;
import org.flow.core.services.TaskOutputService;
import org.flow.executor.ExecutionStateStore;
import org.flow.executor.ExecutorContext;
import org.flow.executor.ExecutorMessageHandler;
import org.flow.executor.ExecutorService;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class SubflowExecutionResultMessageHandler implements ExecutorMessageHandler<SubflowExecutionResult> {
    private final ExecutorService executorService;
    private final MetricRegistry metricRegistry;
    private final ExecutionService executionService;

    private final ExecutionStateStore executionStateStore;

    private final TaskOutputService taskOutputService;

    private final KillSwitchService killSwitchService;

    @Inject
    public SubflowExecutionResultMessageHandler(
        ExecutorService executorService,
        MetricRegistry metricRegistry,
        ExecutionService executionService,
        ExecutionStateStore executionStateStore,
        TaskOutputService taskOutputService,
        KillSwitchService killSwitchService) {
        this.executorService = executorService;
        this.metricRegistry = metricRegistry;
        this.executionService = executionService;
        this.executionStateStore = executionStateStore;
        this.taskOutputService = taskOutputService;
        this.killSwitchService = killSwitchService;
    }

    @Override
    public Optional<ExecutorContext> handle(SubflowExecutionResult message) {
        if (killSwitchService.evaluate(message.getExecutionId()) != EvaluationType.PASS) {
            log.warn("Ignoring subflow execution result for child execution {} as there is a kill switch in it", message.getExecutionId());
            return Optional.empty();
        }
        if (killSwitchService.evaluate(message.getParentTaskRun()) != EvaluationType.PASS) {
            log.warn("Ignoring subflow execution result for parent execution {} as there is a kill switch in it", message.getParentTaskRun().getExecutionId());
            return Optional.empty();
        }

        if (log.isDebugEnabled()) {
            executorService.log(log, true, message);
        }

        return executionStateStore.lock(message.getParentTaskRun().getExecutionId(), execution ->
        {
            ExecutorContext current = new ExecutorContext(execution);

            if (execution.hasTaskRunJoinable(message.getParentTaskRun())) {
                try {
                    TaskRun taskRun = message.getParentTaskRun();
                    taskOutputService.saveOutputs(taskRun, message.getOutputs());

                    Execution newExecution = current.getExecution().withTaskRun(taskRun);

                    // If the worker task result is killed, we must check if it has a parents to also kill them if not already done.
                    // Running flowable tasks that have child tasks running in the worker will be killed thanks to that.
                    if (taskRun.getState().getCurrent() == State.Type.KILLED && taskRun.getParentTaskRunId() != null) {
                        newExecution = executionService.killParentTaskruns(taskRun, newExecution);
                    }

                    current = current.withExecution(newExecution, "joinSubflowExecutionResult");

                    // send metrics on parent taskRun terminated
                    if (taskRun.getState().isTerminated()) {
                        metricRegistry
                            .counter(MetricRegistry.METRIC_EXECUTOR_TASKRUN_ENDED_COUNT, MetricRegistry.METRIC_EXECUTOR_TASKRUN_ENDED_COUNT_DESCRIPTION, metricRegistry.tags(message))
                            .increment();

                        metricRegistry
                            .timer(MetricRegistry.METRIC_EXECUTOR_TASKRUN_ENDED_DURATION, MetricRegistry.METRIC_EXECUTOR_TASKRUN_ENDED_DURATION_DESCRIPTION, metricRegistry.tags(message))
                            .record(taskRun.getState().getDurationOrComputeIt());

                        log.trace("TaskRun terminated: {}", taskRun);
                    }

                    // join worker result
                    return current;
                } catch (InternalException e) {
                    return executorService.handleFailedExecutionFromExecutor(current, e);
                } catch (FlowNotFoundException e) {
                    // avoid infinite for FlowNotFoundException
                    if (!current.getExecution().getState().getCurrent().isFailed()) {
                        return executorService.handleFailedExecutionFromExecutor(current, e);
                    }
                }
            }

            return null;
        });
    }
}
