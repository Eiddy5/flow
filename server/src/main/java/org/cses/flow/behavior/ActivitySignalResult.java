package org.cses.flow.behavior;

import java.util.Map;

public record ActivitySignalResult(Map<String, Object> outputVariables) {

    public ActivitySignalResult {
        outputVariables = Map.copyOf(outputVariables);
    }

    public static ActivitySignalResult completed(Map<String, Object> outputVariables) {
        return new ActivitySignalResult(outputVariables);
    }
}
