package org.cses.flow.runtime.execution;

import java.util.Objects;

public final class OperationScheduler {

    private final ExecutionQueue queue;

    public OperationScheduler(ExecutionQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    public void plan(EngineOperation operation) {
        queue.plan(operation);
    }
}
