package org.cses.flow.task.command;

import java.util.Map;
import java.util.Objects;

public record CompleteTaskRequest(
        String taskId,
        Map<String, Object> result,
        String operatorId,
        String idempotencyKey) {

    public CompleteTaskRequest {
        Objects.requireNonNull(taskId, "taskId");
        result = Map.copyOf(Objects.requireNonNull(result, "result"));
        Objects.requireNonNull(operatorId, "operatorId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
