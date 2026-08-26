package org.cses.flow.core.services.flows.commands;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.services.Command;

public record DeleteFlowCommand(
    String key,
    Boolean draft
)
    implements Command<Flow> {

    public DeleteFlowCommand {
        draft = draft == null ? Boolean.TRUE : draft;
    }

    public static DeleteFlowCommand from(String key) {
        return from(key, true);
    }

    public static DeleteFlowCommand from(String key, Boolean draft) {
        return new DeleteFlowCommand(key, draft);
    }

    @Override
    public void validate() {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Flow key must not be blank");
        }
    }
}
