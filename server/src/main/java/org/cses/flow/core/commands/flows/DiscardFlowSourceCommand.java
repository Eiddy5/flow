package org.cses.flow.core.commands.flows;

import org.cses.flow.core.commands.shared.Command;
import org.cses.flow.core.commands.shared.FlowCommandValidation;
import org.cses.flow.core.domains.flows.FlowWithSource;

public final class DiscardFlowSourceCommand
    implements Command<FlowWithSource> {

    private final String id;

    public DiscardFlowSourceCommand(String id) {
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
