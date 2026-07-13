package org.cses.flow.task.service;

import java.util.Objects;
import org.cses.flow.runtime.engine.FlowEngine;
import org.cses.flow.runtime.model.TaskCompletedSignal;
import org.cses.flow.task.command.CompleteTaskCommand;
import org.cses.flow.task.model.Task;
import org.cses.flow.task.repository.TaskRepository;

public final class TaskService {

    private final TaskRepository taskRepository;
    private final FlowEngine flowEngine;

    public TaskService(TaskRepository taskRepository, FlowEngine flowEngine) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.flowEngine = Objects.requireNonNull(flowEngine, "flowEngine");
    }

    public void complete(CompleteTaskCommand command) {
        Task task = taskRepository.findById(command.taskId())
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + command.taskId()));
        synchronized (task) {
            flowEngine.handleSignal(new TaskCompletedSignal(
                    task.id(),
                    task.processId(),
                    task.executorId(),
                    task.activityId(),
                    command.result(),
                    command.operatorId(),
                    command.idempotencyKey()));
        }
    }
}
