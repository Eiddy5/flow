package org.cses.flow.core.commands.flows;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;
import org.cses.flow.core.domains.flows.Flow;

public record DeployFlowCommand(String id) implements Command<Flow> {

    @Override
    public void validate() {
        FlowCommandValidation.requireFlowId(id);
    }
}
