package org.cses.flow.runtime.command;

import java.util.Objects;
import org.cses.flow.behavior.ActivityBehavior;
import org.cses.flow.behavior.ActivityBehaviorRegistry;
import org.cses.flow.behavior.ActivitySignalContext;
import org.cses.flow.behavior.ActivitySignalResult;
import org.cses.flow.definition.model.Node;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.execution.ExecutionOperationFactory;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.ActivityState;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.ExecutorState;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.TaskCompletedSignal;
import org.cses.flow.runtime.repository.ActivityRepository;
import org.cses.flow.runtime.repository.ProcessRepository;
import org.cses.flow.task.model.Task;
import org.cses.flow.task.model.TaskState;
import org.cses.flow.task.repository.TaskRepository;

public final class HandleSignalCommand implements Command<Process> {

    private final TaskCompletedSignal signal;
    private final ProcessRepository processRepository;
    private final ActivityRepository activityRepository;
    private final TaskRepository taskRepository;
    private final ActivityBehaviorRegistry behaviorRegistry;
    private final ExecutionOperationFactory operationFactory;

    public HandleSignalCommand(
            TaskCompletedSignal signal,
            ProcessRepository processRepository,
            ActivityRepository activityRepository,
            TaskRepository taskRepository,
            ActivityBehaviorRegistry behaviorRegistry,
            ExecutionOperationFactory operationFactory) {
        this.signal = Objects.requireNonNull(signal, "signal");
        this.processRepository = Objects.requireNonNull(processRepository, "processRepository");
        this.activityRepository = Objects.requireNonNull(activityRepository, "activityRepository");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.behaviorRegistry = Objects.requireNonNull(behaviorRegistry, "behaviorRegistry");
        this.operationFactory = Objects.requireNonNull(operationFactory, "operationFactory");
    }

    @Override
    public Process execute(FlowContext flowContext) {
        Process process = flowContext.process();
        Task task = taskRepository.findById(signal.taskId())
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + signal.taskId()));

        validateRelations(process, task);
        if (task.completedWith(signal.idempotencyKey())) {
            return process;
        }
        if (task.state() != TaskState.CREATED && task.state() != TaskState.CLAIMED) {
            throw new IllegalStateException("Task is not waiting for completion: " + task.id());
        }

        Activity activity = activityRepository.findById(task.activityId())
                .orElseThrow(() -> new IllegalStateException("Activity not found: " + task.activityId()));
        Executor executor = process.executor(task.executorId());
        if (activity.state() != ActivityState.RUNNING) {
            throw new IllegalStateException("Activity is not running: " + activity.id());
        }
        if (executor.state() != ExecutorState.WAITING) {
            throw new IllegalStateException("Executor is not waiting: " + executor.id());
        }

        Node node = flowContext.flow().node(task.nodeId());
        ActivityBehavior behavior = behaviorRegistry.get(node.type());
        ActivitySignalResult result = behavior.handleSignal(
                new ActivitySignalContext(flowContext, executor, activity, task, node, signal));

        task.complete(signal.payload(), signal.operatorId(), signal.idempotencyKey());
        activity.complete(result.outputVariables());
        executor.activate();
        taskRepository.save(task);
        activityRepository.save(activity);
        processRepository.save(process);
        flowContext.executionQueue().plan(operationFactory.takeOutgoingEdges(executor.id()));
        return process;
    }

    private void validateRelations(Process process, Task task) {
        if (!task.processId().equals(process.id())
                || !task.processId().equals(signal.processId())
                || !task.executorId().equals(signal.executorId())
                || !task.activityId().equals(signal.activityId())) {
            throw new IllegalArgumentException("Signal, Task, and Process relations do not match");
        }
    }
}
