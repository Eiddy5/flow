package org.flow.core.runners;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.flow.core.models.HasUID;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.queues.event.DispatchEvent;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@AllArgsConstructor
@Builder
public class WorkerTaskResult implements DispatchEvent, HasUID {
    @NotNull
    @With
    TaskRun taskRun;

    /** Owning job; differs from the TaskRun ID for WorkingDirectory child results. */
    @Nullable
    @With
    String workerJobId;

    @Nullable
    @With
    String workerDeliveryId;

    List<TaskRun> dynamicTaskRuns;

    @Nullable
    @With
    @JsonInclude(JsonInclude.Include.ALWAYS)
    Map<String, Object> outputs;

    public WorkerTaskResult(TaskRun taskRun, List<TaskRun> dynamicTaskRuns, Map<String, Object> outputs) {
        this(taskRun, null, null, dynamicTaskRuns, outputs);
    }

    public WorkerTaskResult(TaskRun taskRun) {
        this(taskRun, new ArrayList<>(1), null); // there are usually very few dynamic task runs, so we init the list with a capacity of 1
    }

    public WorkerTaskResult(TaskRun taskRun, Map<String, Object> outputs) {
        this(taskRun, new ArrayList<>(1), outputs); // there are usually very few dynamic task runs, so we init the list with a capacity of 1
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String uid() {
        return taskRun.getId();
    }

    /** Stable across the queue's microsecond-precision JSON timestamp encoding. */
    public String completionId() {
        return taskRun.getState().getEndDate()
            .map(date -> date.truncatedTo(ChronoUnit.MICROS).toString())
            .orElse(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String key() {
        return uid();
    }
}
