package org.cses.flow.runtime.engine;

import java.util.Objects;
import org.cses.flow.behavior.ActivityBehaviorRegistry;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.repository.FlowRepository;
import org.cses.flow.runtime.command.CommandExecutor;
import org.cses.flow.runtime.command.HandleSignalCommand;
import org.cses.flow.runtime.command.StartFlowCommand;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.context.FlowContextFactory;
import org.cses.flow.runtime.execution.ExecutionOperationFactory;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.TaskCompletedSignal;
import org.cses.flow.runtime.repository.ActivityRepository;
import org.cses.flow.runtime.repository.ProcessRepository;
import org.cses.flow.shared.IdGenerator;
import org.cses.flow.task.model.Task;
import org.cses.flow.task.repository.TaskRepository;

public final class FlowEngine {

    private final FlowRepository flowRepository;
    private final ProcessRepository processRepository;
    private final ActivityRepository activityRepository;
    private final TaskRepository taskRepository;
    private final IdGenerator idGenerator;
    private final FlowContextFactory flowContextFactory;
    private final CommandExecutor commandExecutor;
    private final ExecutionOperationFactory operationFactory;
    private final ActivityBehaviorRegistry behaviorRegistry;

    public FlowEngine(
            FlowRepository flowRepository,
            ProcessRepository processRepository,
            ActivityRepository activityRepository,
            TaskRepository taskRepository,
            IdGenerator idGenerator,
            FlowContextFactory flowContextFactory,
            CommandExecutor commandExecutor,
            ExecutionOperationFactory operationFactory,
            ActivityBehaviorRegistry behaviorRegistry) {
        this.flowRepository = Objects.requireNonNull(flowRepository, "flowRepository");
        this.processRepository = Objects.requireNonNull(processRepository, "processRepository");
        this.activityRepository = Objects.requireNonNull(activityRepository, "activityRepository");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.flowContextFactory = Objects.requireNonNull(flowContextFactory, "flowContextFactory");
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
        this.operationFactory = Objects.requireNonNull(operationFactory, "operationFactory");
        this.behaviorRegistry = Objects.requireNonNull(behaviorRegistry, "behaviorRegistry");
    }

    public Process start(String flowId) {
        Flow requestedFlow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));
        Flow deployedFlow = flowRepository.findLatestDeployed(requestedFlow.key())
                .orElseThrow(() -> new IllegalStateException(
                        "No deployed Flow exists for key: " + requestedFlow.key()));
        FlowContext flowContext = flowContextFactory.create(deployedFlow);
        return commandExecutor.execute(
                flowContext,
                new StartFlowCommand(idGenerator, processRepository, operationFactory));
    }

    public Process handleSignal(TaskCompletedSignal signal) {
        Task task = taskRepository.findById(signal.taskId())
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + signal.taskId()));
        Process process = processRepository.findById(task.processId())
                .orElseThrow(() -> new IllegalStateException("Process not found: " + task.processId()));
        Flow flow = flowRepository.findById(process.flowId())
                .orElseThrow(() -> new IllegalStateException("Flow not found: " + process.flowId()));
        FlowContext flowContext = flowContextFactory.create(flow, process);
        return commandExecutor.execute(
                flowContext,
                new HandleSignalCommand(
                        signal,
                        processRepository,
                        activityRepository,
                        taskRepository,
                        behaviorRegistry,
                        operationFactory));
    }
}
