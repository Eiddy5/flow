package org.cses.flow.task.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.cses.flow.behavior.TaskDefinition;
import org.cses.flow.definition.model.Node;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.Process;

public final class Task {

    private final String id;
    private final String processId;
    private final String executorId;
    private final String activityId;
    private final String nodeId;
    private final String name;
    private final TaskType type;
    private final Instant createdAt;
    private TaskState state;
    private Map<String, Object> result = Map.of();
    private String completedBy;
    private String idempotencyKey;
    private Instant completedAt;

    public Task(
            String id,
            Process process,
            Executor executor,
            Activity activity,
            Node node,
            TaskDefinition definition) {
        this.id = Objects.requireNonNull(id, "id");
        this.processId = process.id();
        this.executorId = executor.id();
        this.activityId = activity.id();
        this.nodeId = node.id();
        this.name = definition.name();
        this.type = TaskType.MANUAL;
        this.state = TaskState.CREATED;
        this.createdAt = Instant.now();
    }

    public void complete(
            Map<String, Object> result,
            String operatorId,
            String idempotencyKey) {
        if (state != TaskState.CREATED && state != TaskState.CLAIMED) {
            throw new IllegalStateException("Task cannot complete from state " + state + ": " + id);
        }
        this.result = Map.copyOf(result);
        this.completedBy = Objects.requireNonNull(operatorId, "operatorId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.state = TaskState.COMPLETED;
        this.completedAt = Instant.now();
    }

    public boolean completedWith(String idempotencyKey) {
        return state == TaskState.COMPLETED && Objects.equals(this.idempotencyKey, idempotencyKey);
    }

    public String id() {
        return id;
    }

    public String processId() {
        return processId;
    }

    public String executorId() {
        return executorId;
    }

    public String activityId() {
        return activityId;
    }

    public String nodeId() {
        return nodeId;
    }

    public String name() {
        return name;
    }

    public TaskType type() {
        return type;
    }

    public TaskState state() {
        return state;
    }

    public Map<String, Object> result() {
        return result;
    }

    public String completedBy() {
        return completedBy;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }
}
