package org.cses.flow.core.services.executions.commands;

import org.cses.flow.core.services.Command;
import org.cses.flow.core.domains.executions.Execution;

public record CreateExecutionCommand(
    String executionId,
    String flowKey,
    Long expectedFlowVersion
) implements Command<Execution> {

    public static CreateExecutionCommand from(String flowKey) {
        return new CreateExecutionCommand(flowKey);
    }

    public static CreateExecutionCommand from(
        String executionId,
        String flowKey,
        long expectedFlowVersion
    ) {
        return new CreateExecutionCommand(
            executionId,
            flowKey,
            expectedFlowVersion
        );
    }

    public static CreateExecutionCommand from(
        String executionId,
        String flowKey,
        Long expectedFlowVersion
    ) {
        return new CreateExecutionCommand(
            executionId,
            flowKey,
            expectedFlowVersion
        );
    }

    public CreateExecutionCommand(String flowKey) {
        this(null, flowKey, null);
    }

    public CreateExecutionCommand(
        String executionId,
        String flowKey,
        long expectedFlowVersion
    ) {
        this(executionId, flowKey, Long.valueOf(expectedFlowVersion));
    }

    @Override
    public void validate() {
        if (flowKey == null || flowKey.isBlank()) {
            throw new IllegalArgumentException("Flow key must not be blank");
        }
        if ((executionId == null) != (expectedFlowVersion == null)) {
            throw new IllegalArgumentException(
                "Execution id and expected Flow version must both be "
                    + "present or absent"
            );
        }
        if (executionId != null && executionId.isBlank()) {
            throw new IllegalArgumentException(
                "Execution id must not be blank"
            );
        }
        if (expectedFlowVersion != null && expectedFlowVersion < 1) {
            throw new IllegalArgumentException(
                "Expected Flow version must be positive"
            );
        }
    }
}
