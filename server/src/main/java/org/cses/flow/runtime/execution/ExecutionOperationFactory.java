package org.cses.flow.runtime.execution;

import java.util.Objects;
import org.cses.flow.behavior.ActivityBehaviorRegistry;
import org.cses.flow.runtime.repository.ActivityRepository;
import org.cses.flow.runtime.repository.ProcessRepository;
import org.cses.flow.shared.IdGenerator;
import org.cses.flow.task.repository.TaskRepository;

public final class ExecutionOperationFactory {

    private final IdGenerator idGenerator;
    private final ProcessRepository processRepository;
    private final ActivityRepository activityRepository;
    private final TaskRepository taskRepository;
    private final ActivityBehaviorRegistry behaviorRegistry;

    public ExecutionOperationFactory(
            IdGenerator idGenerator,
            ProcessRepository processRepository,
            ActivityRepository activityRepository,
            TaskRepository taskRepository,
            ActivityBehaviorRegistry behaviorRegistry) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.processRepository = Objects.requireNonNull(processRepository, "processRepository");
        this.activityRepository = Objects.requireNonNull(activityRepository, "activityRepository");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.behaviorRegistry = Objects.requireNonNull(behaviorRegistry, "behaviorRegistry");
    }

    public ExecutionOperation continueExecutor(String executorId) {
        return new ContinueExecutorOperation(executorId, this);
    }

    public ExecutionOperation takeOutgoingEdges(String executorId) {
        return new TakeOutgoingEdgesOperation(executorId, this);
    }

    IdGenerator idGenerator() {
        return idGenerator;
    }

    ProcessRepository processRepository() {
        return processRepository;
    }

    ActivityRepository activityRepository() {
        return activityRepository;
    }

    TaskRepository taskRepository() {
        return taskRepository;
    }

    ActivityBehaviorRegistry behaviorRegistry() {
        return behaviorRegistry;
    }
}
