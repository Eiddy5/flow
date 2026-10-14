package org.flow.core.executor.command;

import java.time.Instant;

import org.flow.core.events.EventId;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;

import jakarta.annotation.Nullable;

public record ChangeTaskRunState(String tenantId,
    String namespace,
    String flowId,
    String executionId,
    Instant timestamp,
    EventId eventId,
    String taskRunId,
    State.Type state,
    @Nullable String operationId) implements ExecutionCommand {
    public static ChangeTaskRunState from(Execution execution, String taskRunId, State.Type state) {
        return new ChangeTaskRunState(
            execution.getTenantId(),
            execution.getNamespace(),
            execution.getFlowId(),
            execution.getId(),
            Instant.now(),
            EventId.create(),
            taskRunId,
            state,
            null
        );
    }

    public ChangeTaskRunState withOperationId(String operationId) {
        return new ChangeTaskRunState(tenantId, namespace, flowId, executionId, timestamp, eventId, taskRunId, state, operationId);
    }
}
