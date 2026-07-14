package org.cses.flow.runtime.model;

import java.util.Map;
import java.util.Objects;

public record TaskCompletedSignal(
        String taskId,
        Map<String, Object> result,
        String operatorId,
        String idempotencyKey) implements Signal {

    public TaskCompletedSignal {
        Objects.requireNonNull(taskId, "taskId");
        result = Map.copyOf(Objects.requireNonNull(result, "result"));
        Objects.requireNonNull(operatorId, "operatorId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
