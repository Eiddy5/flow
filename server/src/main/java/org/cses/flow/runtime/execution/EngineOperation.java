package org.cses.flow.runtime.execution;

import org.cses.flow.runtime.context.CommandContext;

@FunctionalInterface
public interface EngineOperation {

    void execute(CommandContext context);
}
