package org.flow.executor.handler;

import java.util.List;
import java.util.Optional;

import org.flow.core.exceptions.FlowNotFoundException;
import org.flow.core.exceptions.InternalException;
import org.flow.core.killswitch.EvaluationType;
import org.flow.core.killswitch.KillSwitchService;
import org.flow.core.models.executions.Execution;
import org.flow.core.runners.FlowMetaStoreInterface;
import org.flow.core.runners.WorkerTaskResult;
import org.flow.executor.ExecutionStateStore;
import org.flow.executor.ExecutorContext;
import org.flow.executor.ExecutorMessageHandler;
import org.flow.executor.ExecutorService;
import org.flow.executor.KillSwitchActionService;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class WorkerTaskResultMessageHandler implements ExecutorMessageHandler<WorkerTaskResult> {
    private final ExecutionStateStore executionStateStore;

    private final ExecutorService executorService;

    private final FlowMetaStoreInterface flowMetaStore;

    private final KillSwitchService killSwitchService;
    private final KillSwitchActionService killSwitchActionService;

    private final List<WorkerTaskResultListener> workerTaskResultListeners;

    @Inject
    public WorkerTaskResultMessageHandler(
        ExecutionStateStore executionStateStore,
        ExecutorService executorService,
        FlowMetaStoreInterface flowMetaStore,
        KillSwitchService killSwitchService,
        KillSwitchActionService killSwitchActionService,
        List<WorkerTaskResultListener> workerTaskResultListeners) {
        this.executionStateStore = executionStateStore;
        this.executorService = executorService;
        this.flowMetaStore = flowMetaStore;
        this.killSwitchService = killSwitchService;
        this.killSwitchActionService = killSwitchActionService;
        this.workerTaskResultListeners = workerTaskResultListeners;
    }

    @Override
    public Optional<ExecutorContext> handle(WorkerTaskResult message) {
        EvaluationType evaluationType = killSwitchService.evaluate(message.getTaskRun());
        if (evaluationType != EvaluationType.PASS) {
            var execution = executionStateStore.findByIdWithoutAcl(message.getTaskRun().getExecutionId());
            if (execution != null && evaluationType.isKillSwitched(execution)) {
                killSwitchActionService.handle(evaluationType, execution.getTenantId(), execution.getId());
                return Optional.empty();
            }
        }

        if (log.isDebugEnabled()) {
            executorService.log(log, true, message);
        }

        Optional<ExecutorContext> result = executionStateStore.lock(message.getTaskRun().getExecutionId(), execution ->
        {
            ExecutorContext current = new ExecutorContext(execution);

            if (execution.hasTaskRunJoinable(message.getTaskRun())) {
                try {
                    // process worker task result
                    executorService.addWorkerTaskResult(
                        current,
                        () -> flowMetaStore.findByExecutionForRuntime(execution).orElseThrow(() -> new FlowNotFoundException(execution)),
                        message
                    );
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

        // a present result means the taskrun was joined here (redeliveries come back empty): notify listeners once
        if (result.isPresent()) {
            notifyJoined(message, result.get().getExecution());
        }

        return result;
    }

    private void notifyJoined(WorkerTaskResult message, Execution execution) {
        for (WorkerTaskResultListener listener : workerTaskResultListeners) {
            try {
                listener.onJoined(message, execution);
            } catch (Exception e) {
                log.error("Worker task result listener {} failed", listener.getClass().getName(), e);
            }
        }
    }
}
