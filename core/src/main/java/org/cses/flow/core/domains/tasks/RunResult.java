package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.runner.RunContext;

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
        if (targetState != State.Type.SUCCESS
            && targetState != State.Type.WARNING
            && targetState != State.Type.FAILED
            && targetState != State.Type.KILLED) {
            throw new IllegalArgumentException(
                "Runnable target state must be SUCCESS, WARNING, FAILED "
                    + "or KILLED"
            );
        }
        if (targetState == State.Type.FAILED
            && (error == null || error.isBlank())) {
            throw new IllegalArgumentException(
                "Failed Runnable result must carry an error"
            );
        }
        if (targetState != State.Type.FAILED && error != null) {
            throw new IllegalArgumentException(
                "Only a failed Runnable result may carry an error"
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

    public static RunResult success(Map<String, ?> outputs) {
        return new RunResult(State.Type.SUCCESS, outputs, null);
    }

    public static RunResult warning(Map<String, ?> outputs) {
        return new RunResult(State.Type.WARNING, outputs, null);
    }

    public static RunResult failed(String error) {
        return new RunResult(State.Type.FAILED, Map.of(), error);
    }

    public static RunResult killed() {
        return new RunResult(State.Type.KILLED, Map.of(), null);
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
