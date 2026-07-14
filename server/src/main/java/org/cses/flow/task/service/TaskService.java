package org.cses.flow.task.service;

import java.util.Objects;
import org.cses.flow.runtime.engine.FlowEngine;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.TaskCompletedSignal;
import org.cses.flow.task.command.CompleteTaskRequest;

public final class TaskService {

    private final FlowEngine flowEngine;

    public TaskService(FlowEngine flowEngine) {
        this.flowEngine = Objects.requireNonNull(flowEngine, "flowEngine");
    }

    public Process complete(CompleteTaskRequest request) {
        return flowEngine.handleSignal(new TaskCompletedSignal(
                request.taskId(),
                request.result(),
                request.operatorId(),
                request.idempotencyKey()));
    }
}
