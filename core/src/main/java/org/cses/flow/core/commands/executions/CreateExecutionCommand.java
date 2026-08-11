package org.cses.flow.core.commands.executions;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;
import org.cses.flow.core.domains.executions.Execution;

public final class CreateExecutionCommand implements Command<Execution> {

    private final String executionId;
    private final String flowId;
    private final Long expectedFlowReversion;
    private final boolean startImmediately;

    public CreateExecutionCommand(String flowId) {
        this(null, flowId, null, true);
    }

    public CreateExecutionCommand(
        String flowId,
        boolean startImmediately
    ) {
        this(null, flowId, null, startImmediately);
    }

    public CreateExecutionCommand(
        String executionId,
        String flowId,
        long expectedFlowReversion,
        boolean startImmediately
    ) {
        this(
            executionId,
            flowId,
            Long.valueOf(expectedFlowReversion),
            startImmediately
        );
    }

    private CreateExecutionCommand(
        String executionId,
        String flowId,
        Long expectedFlowReversion,
        boolean startImmediately
    ) {
        this.executionId = executionId;
        this.flowId = flowId;
        this.expectedFlowReversion = expectedFlowReversion;
        this.startImmediately = startImmediately;
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

    public boolean startImmediately() {
        return startImmediately;
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
