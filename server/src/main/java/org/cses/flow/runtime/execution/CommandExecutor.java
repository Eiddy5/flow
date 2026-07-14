package org.cses.flow.runtime.execution;

import java.util.Objects;
import org.cses.flow.runtime.command.Command;
import org.cses.flow.runtime.context.CommandContext;
import org.cses.flow.runtime.context.CommandContextFactory;

public final class CommandExecutor {

    private final CommandContextFactory contextFactory;
    private final ExecutionRunner executionRunner;

    public CommandExecutor(CommandContextFactory contextFactory, ExecutionRunner executionRunner) {
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
        this.executionRunner = Objects.requireNonNull(executionRunner, "executionRunner");
    }

    public <T> T execute(Command<T> command) {
        CommandContext context = contextFactory.open();
        try {
            context.executionQueue().plan(new CommandOperation<>(command));
            executionRunner.execute(context);
            context.commit();
            return context.result();
        } catch (Throwable failure) {
            context.rollback(failure);
            throw failure;
        } finally {
            context.close();
        }
    }
}
