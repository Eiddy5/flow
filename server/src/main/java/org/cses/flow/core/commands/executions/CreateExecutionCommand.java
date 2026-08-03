package org.cses.flow.core.commands.executions;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;
import org.cses.flow.core.domains.executions.Execution;

public final class CreateExecutionCommand implements Command<Execution> {

    private final String flowId;

    public CreateExecutionCommand(String flowId) {
        this.flowId = flowId;
    }

    public String flowId() {
        return flowId;
    }

    @Override
    public void validate() {
        FlowCommandValidation.requireFlowId(flowId);
    }
}
