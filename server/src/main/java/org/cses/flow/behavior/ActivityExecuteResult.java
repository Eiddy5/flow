package org.cses.flow.behavior;

import java.util.Map;

public record ActivityExecuteResult(
        ActivityExecutionStatus status,
        TaskDefinition taskDefinition,
        Map<String, Object> outputVariables) {

    public ActivityExecuteResult {
        outputVariables = Map.copyOf(outputVariables);
    }

    public static ActivityExecuteResult completed() {
        return new ActivityExecuteResult(ActivityExecutionStatus.COMPLETED, null, Map.of());
    }

    public static ActivityExecuteResult waiting(TaskDefinition taskDefinition) {
        return new ActivityExecuteResult(ActivityExecutionStatus.WAITING, taskDefinition, Map.of());
    }
}
