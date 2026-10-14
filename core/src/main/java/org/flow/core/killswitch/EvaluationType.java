package org.flow.core.killswitch;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;

public enum EvaluationType {
    PASS,
    KILL,
    CANCEL,
    IGNORE;

    public boolean isKillSwitched(Execution execution) {
        if (this != EvaluationType.PASS) {
            // to avoid loops with EvaluationType.KILL, we should not kill when the execution is killing
            return this != EvaluationType.KILL || (execution.getState().getCurrent() != State.Type.KILLING && execution.getState().getCurrent() != State.Type.KILLED);
        }
        return false;
    }
}
