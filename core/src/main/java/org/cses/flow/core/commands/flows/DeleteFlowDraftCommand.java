package org.cses.flow.core.commands.flows;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.commands.FlowCommandValidation;
import org.cses.flow.core.domains.flows.FlowDraft;

public record DeleteFlowDraftCommand(String id)
    implements Command<FlowDraft> {

    @Override
    public void validate() {
        FlowCommandValidation.requireFlowId(id);
    }
}
