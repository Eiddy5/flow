package org.cses.flow.runtime.engine;

import java.util.Objects;
import org.cses.flow.runtime.command.HandleSignalCommand;
import org.cses.flow.runtime.command.StartFlowCommand;
import org.cses.flow.runtime.execution.CommandExecutor;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.TaskCompletedSignal;

public final class FlowEngine {

    private final CommandExecutor commandExecutor;

    public FlowEngine(CommandExecutor commandExecutor) {
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
    }

    public Process start(String flowId) {
        return commandExecutor.execute(new StartFlowCommand(flowId));
    }

    public Process handleSignal(TaskCompletedSignal signal) {
        return commandExecutor.execute(new HandleSignalCommand(signal));
    }
}
