package org.cses.flow.core.commands.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.commands.shared.Command;
import org.cses.flow.core.commands.shared.FlowCommandValidation;

public final class CloseFlowCommand implements Command<Flow> {

    private final String id;

    public CloseFlowCommand(String id) {
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
