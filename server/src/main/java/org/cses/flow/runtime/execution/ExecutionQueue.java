package org.cses.flow.runtime.execution;

import java.util.ArrayDeque;
import java.util.Objects;

public final class ExecutionQueue {

    private final ArrayDeque<ExecutionOperation> operations = new ArrayDeque<>();

    public void plan(ExecutionOperation operation) {
        operations.addLast(Objects.requireNonNull(operation, "operation"));
    }

    public ExecutionOperation poll() {
        ExecutionOperation operation = operations.pollFirst();
        if (operation == null) {
            throw new IllegalStateException("ExecutionQueue is empty");
        }
        return operation;
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }
}
