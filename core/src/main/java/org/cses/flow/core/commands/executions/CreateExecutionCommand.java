package org.cses.flow.core.commands.executions;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;
import org.cses.flow.core.domains.executions.Execution;

public final class CreateExecutionCommand implements Command<Execution> {

    private final String flowId;
    private final boolean startImmediately;

    public CreateExecutionCommand(String flowId) {
        this(flowId, true);
    }

    public CreateExecutionCommand(
        String flowId,
        boolean startImmediately
    ) {
        this.flowId = flowId;
        this.startImmediately = startImmediately;
    }

    public String flowId() {
        return flowId;
    }

    public boolean startImmediately() {
        return startImmediately;
    }

    @Override
    public void validate() {
        FlowCommandValidation.requireFlowId(flowId);
    }
}
