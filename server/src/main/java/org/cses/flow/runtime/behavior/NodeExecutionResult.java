package org.cses.flow.runtime.behavior;

import java.util.Map;
import java.util.Objects;

public sealed interface NodeExecutionResult {

    record Completed(Map<String, Object> output) implements NodeExecutionResult {
        public Completed {
            output = Map.copyOf(Objects.requireNonNull(output, "output"));
        }
    }

    record Waiting(TaskDefinition task) implements NodeExecutionResult {
        public Waiting {
            Objects.requireNonNull(task, "task");
        }
    }
}
