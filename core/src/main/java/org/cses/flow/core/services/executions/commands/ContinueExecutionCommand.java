package org.cses.flow.core.services.executions.commands;

import org.cses.flow.core.services.Command;
import org.cses.flow.core.domains.executions.Execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ContinueExecutionCommand(
    String executionId,
    Map<String, Object> inputs
) implements Command<Execution> {

    public static ContinueExecutionCommand from(
        String executionId,
        Map<String, ?> inputs
    ) {
        return new ContinueExecutionCommand(
            executionId,
            immutableInputs(inputs)
        );
    }

    public ContinueExecutionCommand {
        inputs = immutableInputs(inputs);
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
