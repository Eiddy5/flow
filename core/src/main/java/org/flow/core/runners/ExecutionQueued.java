package org.flow.core.runners;

import java.time.Instant;

import org.flow.core.models.HasUID;
import org.flow.core.models.executions.Execution;
import org.flow.core.utils.IdUtils;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

@Value
@AllArgsConstructor
@Builder
public class ExecutionQueued implements HasUID {
    String tenantId;

    @NotNull
    String namespace;

    @NotNull
    String flowId;

    @NotNull
    Execution execution;

    @NotNull
    Instant date;

    public static ExecutionQueued fromExecutionRunning(ExecutionRunning executionRunning) {
        return new ExecutionQueued(
            executionRunning.getTenantId(),
            executionRunning.getNamespace(),
            executionRunning.getFlowId(),
            executionRunning.getExecution(),
            Instant.now()
        );
    }

    /** {@inheritDoc **/
    @Override
    public String uid() {
        return IdUtils.fromParts(this.tenantId, this.namespace, this.flowId, this.execution.getId());
    }
}
