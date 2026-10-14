package org.flow.core.models.executions;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.flow.core.models.TenantInterface;
import org.flow.core.runners.WorkerTask;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A targeted {@link ExecutionKilled} event that interrupts a subset of an execution's task runs
 * without killing the whole execution — unlike {@link ExecutionKilledExecution}.
 */
@Getter
@SuperBuilder
@EqualsAndHashCode
@ToString
@NoArgsConstructor
public class ExecutionKilledTaskRuns extends ExecutionKilled implements TenantInterface {
    @NotNull
    @JsonInclude
    @Builder.Default
    protected String type = "taskruns";

    /**
     * The execution owning the targeted task runs.
     */
    @NotNull
    String executionId;

    /**
     * The task runs to interrupt.
     */
    @NotNull
    List<String> taskRunIds;

    /**
     * The state to move the targeted task runs to once interrupted.
     */
    @NotNull
    org.flow.core.models.flows.State.Type taskRunState;

    public boolean isFor(WorkerTask workerTask) {
        String taskTenantId = workerTask.getTaskRun().getTenantId();
        String taskExecutionId = workerTask.getTaskRun().getExecutionId();
        return (taskTenantId == null || taskTenantId.equals(this.tenantId))
            && taskExecutionId.equals(this.executionId)
            && this.taskRunIds.contains(workerTask.getTaskRun().getId());
    }

    @Override
    public String uid() {
        return this.executionId;
    }
}
