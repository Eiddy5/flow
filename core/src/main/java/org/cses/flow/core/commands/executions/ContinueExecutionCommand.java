package org.cses.flow.core.commands.executions;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.domains.executions.Execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ContinueExecutionCommand implements Command<Execution> {

    private final String executionId;
    private final Map<String, Object> inputs;

    public ContinueExecutionCommand(
        String executionId,
        Map<String, ?> inputs
    ) {
        this.executionId = executionId;
        this.inputs = immutableInputs(inputs);
    }

    public String executionId() {
        return executionId;
    }

    public Map<String, Object> inputs() {
        return inputs;
    }

    @Override
    public void validate() {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException(
                "Execution id must not be blank"
            );
        }
    }

    private static Map<String, Object> immutableInputs(
        Map<String, ?> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(
            Objects.requireNonNull(key, "Flow input key"),
            Objects.requireNonNull(value, "Flow input value")
        ));
        return Map.copyOf(copied);
    }
}
