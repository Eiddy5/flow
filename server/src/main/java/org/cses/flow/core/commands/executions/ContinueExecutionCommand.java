package org.cses.flow.core.commands.executions;

import org.cses.flow.core.commands.shared.Command;
import org.cses.flow.core.domains.executions.Execution;

public final class ContinueExecutionCommand implements Command<Execution> {

    private final String executionId;

    public ContinueExecutionCommand(String executionId) {
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
