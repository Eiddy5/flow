package org.cses.flow.core.commands.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;

public record DeleteFlowCommand(String id) implements Command<Flow> {

    @Override
    public void validate() {
        FlowCommandValidation.requireFlowId(id);
    }
}
