package org.cses.flow.core.commands.flows;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;
import org.cses.flow.core.domains.flows.FlowDraft;

public final class DeleteFlowDraftCommand
    implements Command<FlowDraft> {

    private final String id;

    public DeleteFlowDraftCommand(String id) {
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
