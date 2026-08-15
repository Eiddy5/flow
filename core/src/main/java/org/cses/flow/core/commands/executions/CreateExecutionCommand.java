package org.cses.flow.core.commands.executions;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;
import org.cses.flow.core.domains.executions.Execution;

public record CreateExecutionCommand(
    String executionId,
    String flowKey,
    Long expectedFlowVersion
) implements Command<Execution> {

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
        FlowCommandValidation.requireFlowKey(flowKey);
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
