package org.cses.flow.runtime.execution;

import org.cses.flow.runtime.context.CommandContext;

public final class ExecutionRunner {

    public void execute(CommandContext commandContext) {
        ExecutionQueue queue = commandContext.executionQueue();
        while (!queue.isEmpty()) {
            queue.poll().execute(commandContext);
        }
    }
}
