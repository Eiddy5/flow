package org.flow.executor.handler;

import java.time.DateTimeException;
import java.util.List;

import org.flow.core.executor.command.Create;
import org.flow.core.executor.command.ExecutionCommand;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionId;
import org.flow.core.models.triggers.multipleflows.MultipleConditionStateStore;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.MultipleConditionEvent;
import org.flow.executor.FlowTriggerService;
import org.flow.executor.MessageHandler;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class MultipleConditionEventMessageHandler implements MessageHandler<MultipleConditionEvent> {
    private final FlowTriggerService flowTriggerService;
    private final MultipleConditionStateStore multipleConditionStateStore;
    private final DispatchQueueInterface<ExecutionCommand> executionCommandQueue;

    @Inject
    public MultipleConditionEventMessageHandler(
        FlowTriggerService flowTriggerService,
        MultipleConditionStateStore multipleConditionStateStore,
        DispatchQueueInterface<ExecutionCommand> executionCommandQueue) {
        this.flowTriggerService = flowTriggerService;
        this.multipleConditionStateStore = multipleConditionStateStore;
        this.executionCommandQueue = executionCommandQueue;
    }

    @Override
    public void handle(MultipleConditionEvent message) {
        final List<Execution> executions;
        try {
            executions = flowTriggerService.computeExecutionsFromFlowTriggerDependsOn(message.execution(), message.flow(), multipleConditionStateStore);
        } catch (DateTimeException | ArithmeticException e) {
            log.error("Skipping flow-trigger evaluation for flow '{}': the trigger window is out of the supported range.", message.flow().getId(), e);
            return;
        }

        executions
            .forEach(exec ->
            {
                try {
                    Create cmd = Create.of(new ExecutionId(exec.getTenantId(), exec.getNamespace(), exec.getFlowId(), exec.getId(), exec.getFlowRevision()))
                        .withKind(exec.getKind())
                        .withTrigger(exec.getTrigger())
                        .withLabels(exec.getLabels())
                        .withInputs(exec.getInputs())
                        .withExecutionDepth(exec.getMetadata().getExecutionDepth());
                    // Preserve terminal state (e.g. FAILED when trigger input rendering fails).
                    if (exec.getState().isTerminated()) {
                        cmd = cmd.withStateType(exec.getState().getCurrent());
                    }
                    executionCommandQueue.emit(cmd);
                } catch (QueueException e) {
                    log.error("Unable to emit the execution {}", exec.getId(), e);
                }
            });
    }
}
