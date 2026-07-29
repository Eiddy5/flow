package org.cses.flow.core.commands.flows;

import org.cses.flow.core.commands.shared.Command;
import org.cses.flow.core.commands.shared.FlowCommandValidation;
import org.cses.flow.core.domains.flows.Flow;

public final class DeployFlowCommand implements Command<Flow> {

    private final String id;

    public DeployFlowCommand(String id) {
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
