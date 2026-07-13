package org.cses.flow.runtime.command;

import java.util.Objects;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.execution.ExecutionOperation;

public final class CommandOperation<T> implements ExecutionOperation {

    private final Command<T> command;

    public CommandOperation(Command<T> command) {
        this.command = Objects.requireNonNull(command, "command");
    }

    @Override
    public void execute(FlowContext flowContext) {
        flowContext.setResult(command.execute(flowContext));
    }
}
