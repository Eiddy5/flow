package org.cses.flow.core.commands.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;

public final class DeleteFlowCommand implements Command<Flow> {

    private final String id;

    public DeleteFlowCommand(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    @Override
    public void validate() {
        FlowCommandValidation.requireFlowId(id);
    }
}
