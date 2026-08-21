package org.cses.flow.core.services.flows.commands;

import org.cses.flow.core.services.Command;
import org.cses.flow.core.domains.flows.Flow;

public record DeployFlowCommand(String key) implements Command<Flow> {

    public static DeployFlowCommand from(String key) {
        return new DeployFlowCommand(key);
    }

    @Override
    public void validate() {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Flow key must not be blank");
        }
    }
}
