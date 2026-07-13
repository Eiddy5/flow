package org.cses.flow.runtime.context;

import java.util.Objects;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.runtime.execution.ExecutionQueue;
import org.cses.flow.runtime.model.Process;

public final class FlowContext {

    private final Flow flow;
    private final ExecutionQueue executionQueue;
    private Process process;
    private Object result;

    FlowContext(Flow flow, Process process) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.process = process;
        this.executionQueue = new ExecutionQueue();
    }

    public Flow flow() {
        return flow;
    }

    public Process process() {
        if (process == null) {
            throw new IllegalStateException("FlowContext has no Process yet");
        }
        return process;
    }

    public void setProcess(Process process) {
        if (this.process != null) {
            throw new IllegalStateException("FlowContext Process is already set");
        }
        this.process = Objects.requireNonNull(process, "process");
    }

    public ExecutionQueue executionQueue() {
        return executionQueue;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    @SuppressWarnings("unchecked")
    public <T> T result() {
        return (T) result;
    }
}
