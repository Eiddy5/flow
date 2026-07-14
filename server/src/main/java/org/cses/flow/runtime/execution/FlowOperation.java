package org.cses.flow.runtime.execution;

import org.cses.flow.runtime.context.CommandContext;
import org.cses.flow.runtime.context.OperationContext;

public abstract class FlowOperation implements EngineOperation {

    @Override
    public final void execute(CommandContext commandContext) {
        execute(commandContext.operationContext());
    }

    protected abstract void execute(OperationContext context);
}
