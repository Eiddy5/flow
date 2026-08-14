package org.cses.flow.core.commands.executions;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.domains.executions.Execution;

public record CancelExecutionCommand(String executionId)
        implements Command<Execution> {

    @Override
    public void validate() {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException(
                    "Execution id must not be blank"
            );
        }
    }
}
