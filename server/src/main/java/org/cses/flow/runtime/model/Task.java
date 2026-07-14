package org.cses.flow.runtime.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

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
    private Map<String, Object> result;
    private String completedBy;
    private String idempotencyKey;
    private Instant completedAt;

    public Task(
            String id,
            String processId,
            String executorId,
            String activityId,
            String nodeId,
            String name) {
        this.id = Objects.requireNonNull(id, "id");
        this.processId = Objects.requireNonNull(processId, "processId");
        this.executorId = Objects.requireNonNull(executorId, "executorId");
        this.activityId = Objects.requireNonNull(activityId, "activityId");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.name = Objects.requireNonNull(name, "name");
        this.type = TaskType.MANUAL;
        this.state = TaskState.CREATED;
        this.result = Map.of();
        this.createdAt = Instant.now();
    }

    private Task(Task source) {
        this.id = source.id;
        this.processId = source.processId;
        this.executorId = source.executorId;
        this.activityId = source.activityId;
        this.nodeId = source.nodeId;
        this.name = source.name;
        this.type = source.type;
        this.createdAt = source.createdAt;
        this.state = source.state;
        this.result = Map.copyOf(source.result);
        this.completedBy = source.completedBy;
        this.idempotencyKey = source.idempotencyKey;
        this.completedAt = source.completedAt;
    }

    public Task copy() { return new Task(this); }

    public void complete(Map<String, Object> result, String operatorId, String idempotencyKey) {
        if (state != TaskState.CREATED && state != TaskState.CLAIMED) {
            throw new IllegalStateException("Task cannot complete from state " + state + ": " + id);
        }
        this.result = Map.copyOf(Objects.requireNonNull(result, "result"));
        this.completedBy = Objects.requireNonNull(operatorId, "operatorId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.state = TaskState.COMPLETED;
        this.completedAt = Instant.now();
    }

    public boolean completedWith(String idempotencyKey) {
        return state == TaskState.COMPLETED && Objects.equals(this.idempotencyKey, idempotencyKey);
    }

    public String id() { return id; }
    public String processId() { return processId; }
    public String executorId() { return executorId; }
    public String activityId() { return activityId; }
    public String nodeId() { return nodeId; }
    public String name() { return name; }
    public TaskType type() { return type; }
    public TaskState state() { return state; }
    public Map<String, Object> result() { return result; }
    public String completedBy() { return completedBy; }
    public String idempotencyKey() { return idempotencyKey; }
    public Instant createdAt() { return createdAt; }
    public Instant completedAt() { return completedAt; }
}
