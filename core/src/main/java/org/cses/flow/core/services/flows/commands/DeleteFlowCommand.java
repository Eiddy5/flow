package org.cses.flow.core.services.flows.commands;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.services.Command;

public record DeleteFlowCommand(String key) implements Command<Flow> {

    public static DeleteFlowCommand from(String key) {
        return new DeleteFlowCommand(key);
    }

    @Override
    public void validate() {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Flow key must not be blank");
        }
    }
}
