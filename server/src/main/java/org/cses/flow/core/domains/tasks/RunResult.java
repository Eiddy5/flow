package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.domains.flows.State;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable result of one {@link RunnableTask#run(RunContext)} invocation.
 */
public final class RunResult {

    private final State.Type targetState;
    private final Map<String, Object> outputs;
    private final String error;

    private RunResult(
        State.Type targetState,
        Map<String, ?> outputs,
        String error
    ) {
        if (targetState != State.Type.COMPLETED
            && targetState != State.Type.TERMINATED) {
            throw new IllegalArgumentException(
                "Runnable target state must be COMPLETED or TERMINATED"
            );
        }
        if (targetState == State.Type.TERMINATED
            && (error == null || error.isBlank())) {
            throw new IllegalArgumentException(
                "Terminated Runnable result must carry an error"
            );
        }
        if (targetState == State.Type.COMPLETED && error != null) {
            throw new IllegalArgumentException(
                "Completed Runnable result must not carry an error"
            );
        }
        this.targetState = targetState;
        LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
        if (outputs != null) {
            outputs.forEach(copied::put);
        }
        this.outputs = Map.copyOf(copied);
        this.error = error;
    }

    public static RunResult completed(Map<String, ?> outputs) {
        return new RunResult(State.Type.COMPLETED, outputs, null);
    }

    public static RunResult failed(String error) {
        return new RunResult(State.Type.TERMINATED, Map.of(), error);
    }

    public State.Type targetState() {
        return targetState;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    public String error() {
        return error;
    }
}
