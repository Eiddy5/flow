package org.cses.flow.core.commands.executions;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.domains.executions.Execution;

public final class CancelExecutionCommand implements Command<Execution> {

    private final String executionId;

    public CancelExecutionCommand(String executionId) {
        this.executionId = executionId;
    }

    public String executionId() {
        return executionId;
    }

    @Override
    public void validate() {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException(
                "Execution id must not be blank"
            );
        }
    }
}
