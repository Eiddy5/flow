package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.runner.RunContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of one {@link RunnableTask#run(RunContext)} invocation.
 */
public record RunResult(
    State.Type targetState,
    Map<String, Object> outputs,
    String error
) {

    public static RunResult from(
        State.Type targetState,
        Map<String, ?> outputs,
        String error
    ) {
        return new RunResult(targetState, immutableOutputs(outputs), error);
    }

    public RunResult {
        Objects.requireNonNull(targetState, "targetState");
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
        outputs = immutableOutputs(outputs);
    }

    public static RunResult success(Map<String, ?> outputs) {
        return from(
            State.Type.SUCCESS,
            immutableOutputs(outputs),
            null
        );
    }

    public static RunResult warning(Map<String, ?> outputs) {
        return from(
            State.Type.WARNING,
            immutableOutputs(outputs),
            null
        );
    }

    public static RunResult failed(String error) {
        return from(State.Type.FAILED, Map.of(), error);
    }

    public static RunResult killed() {
        return from(State.Type.KILLED, Map.of(), null);
    }

    private static Map<String, Object> immutableOutputs(
        Map<String, ?> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(
            Objects.requireNonNull(key, "Runnable output key"),
            Objects.requireNonNull(value, "Runnable output value")
        ));
        return Map.copyOf(copied);
    }
}
