package org.flow.core.runners;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import org.flow.core.models.executions.ExecutionKind;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.State;
import org.flow.core.models.tasks.Task;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.With;

@Data
@Builder
public class WorkerTask extends WorkerJob {
    public static final String TYPE = "task";

    @NotNull
    @JsonInclude
    private final String type = TYPE;

    @NotNull
    @With
    private TaskRun taskRun;

    @NotNull
    private Task task;

    @NotNull
    private WorkerTaskData data;

    @Nullable
    private ExecutionKind executionKind;

    /** Assigned after a durable claim; never supplied by the dispatch message. */
    @JsonIgnore
    private String workerDeliveryId;

    /**
     * {@inheritDoc}
     */
    @Override
    public String uid() {
        return this.taskRun.getId();
    }

    /**
     * This method will fail the tasks with a FAILED or WARNING state depending on the allowFailure attribute of the task.
     *
     * @return this worker task, updated
     */
    public TaskRun fail() {
        var state = State.Type.fail(task);
        return this.getTaskRun().withState(state);
    }
}
