package org.flow.core.runners;

import org.flow.core.models.HasUID;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.queues.event.DispatchEvent;

public record SubflowExecutionEnd(
    Execution childExecution,
    String parentExecutionId,
    String taskRunId,
    String taskId,
    State.Type state) implements HasUID, DispatchEvent {

    public String toStringState() {
        return "SubflowExecutionEnd(" +
            "childExecutionId=" + childExecution.getId() +
            ", parentExecutionId=" + parentExecutionId +
            ", taskId=" + taskId +
            ", taskRunId=" + taskRunId +
            ", state=" + state +
            ")";
    }

    @Override
    public String uid() {
        return parentExecutionId;
    }

    @Override
    public String key() {
        return uid();
    }
}
