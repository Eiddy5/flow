package org.cses.flow.runtime.command;

import java.util.Objects;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.execution.ExecutionRunner;

public final class CommandExecutor {

    private final ExecutionRunner executionRunner;

    public CommandExecutor(ExecutionRunner executionRunner) {
        this.executionRunner = Objects.requireNonNull(executionRunner, "executionRunner");
    }

    public <T> T execute(FlowContext flowContext, Command<T> command) {
        flowContext.executionQueue().plan(new CommandOperation<>(command));
        executionRunner.execute(flowContext);
        return flowContext.result();
    }
}
