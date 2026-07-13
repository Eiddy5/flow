package org.cses.flow.runtime.model;

import java.util.Map;
import java.util.Objects;

public record TaskCompletedSignal(
        String taskId,
        String processId,
        String executorId,
        String activityId,
        Map<String, Object> payload,
        String operatorId,
        String idempotencyKey) {

    public TaskCompletedSignal {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(processId, "processId");
        Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(activityId, "activityId");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        Objects.requireNonNull(operatorId, "operatorId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
