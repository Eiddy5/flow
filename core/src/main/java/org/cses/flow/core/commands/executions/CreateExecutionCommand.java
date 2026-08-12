package org.cses.flow.core.commands.executions;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;
import org.cses.flow.core.domains.executions.Execution;

public final class CreateExecutionCommand implements Command<Execution> {

    private final String executionId;
    private final String flowId;
    private final Long expectedFlowReversion;

    public CreateExecutionCommand(String flowId) {
        this(null, flowId, null);
    }

    public CreateExecutionCommand(
        String executionId,
        String flowId,
        long expectedFlowReversion
    ) {
        this(
            executionId,
            flowId,
            Long.valueOf(expectedFlowReversion)
        );
    }

    private CreateExecutionCommand(
        String executionId,
        String flowId,
        Long expectedFlowReversion
    ) {
        this.executionId = executionId;
        this.flowId = flowId;
        this.expectedFlowReversion = expectedFlowReversion;
    }

    public String executionId() {
        return executionId;
    }

    public String flowId() {
        return flowId;
    }

    public Long expectedFlowReversion() {
        return expectedFlowReversion;
    }

    @Override
    public void validate() {
        FlowCommandValidation.requireFlowId(flowId);
        if ((executionId == null) != (expectedFlowReversion == null)) {
            throw new IllegalArgumentException(
                "Execution id and expected Flow reversion must both be "
                    + "present or absent"
            );
        }
        if (executionId != null && executionId.isBlank()) {
            throw new IllegalArgumentException(
                "Execution id must not be blank"
            );
        }
        if (expectedFlowReversion != null && expectedFlowReversion < 1) {
            throw new IllegalArgumentException(
                "Expected Flow reversion must be positive"
            );
        }
    }
}
