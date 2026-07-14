package org.cses.flow.runtime.execution;

import java.util.Objects;
import org.cses.flow.runtime.context.CommandContext;
import org.cses.flow.runtime.command.Command;

public final class CommandOperation<T> implements EngineOperation {

    private final Command<T> command;

    public CommandOperation(Command<T> command) {
        this.command = Objects.requireNonNull(command, "command");
    }

    @Override
    public void execute(CommandContext context) {
        context.setResult(command.execute(context));
    }
}
