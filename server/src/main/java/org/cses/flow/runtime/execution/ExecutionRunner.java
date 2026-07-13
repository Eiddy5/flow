package org.cses.flow.runtime.execution;

import org.cses.flow.runtime.context.FlowContext;

public final class ExecutionRunner {

    public void execute(FlowContext flowContext) {
        ExecutionQueue queue = flowContext.executionQueue();
        while (!queue.isEmpty()) {
            ExecutionOperation operation = queue.poll();
            operation.execute(flowContext);
        }
    }
}
