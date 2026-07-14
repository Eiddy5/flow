package org.cses.flow.runtime.execution.operation;

import java.util.Objects;
import org.cses.flow.runtime.context.OperationContext;
import org.cses.flow.runtime.execution.FlowOperation;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.Process;

public final class EndProcessOperation extends FlowOperation {

    private final Executor executor;

    public EndProcessOperation(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    protected void execute(OperationContext context) {
        Process process = context.flowContext().process();
        executor.complete();
        process.completeIfPossible();
        context.runtimeSession().update(process);
    }
}
