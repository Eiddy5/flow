package org.flow.core.models.tasks;

import java.util.List;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.NextTaskRun;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.utils.IdUtils;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;

@Builder
@Value
public class ResolvedTask {
    @NotNull
    Task task;

    String value;

    Integer iteration;

    String parentId;

    public NextTaskRun toNextTaskRun(Execution execution) {
        return new NextTaskRun(
            TaskRun.of(execution, this),
            this.getTask()
        );
    }

    public NextTaskRun toNextTaskRunIncrementIteration(Execution execution, Integer iteration) {
        return new NextTaskRun(
            TaskRun.of(execution, this).withIteration(iteration != null ? iteration : 0),
            this.getTask()
        );
    }

    public static ResolvedTask of(Task task) {
        return ResolvedTask.builder()
            .task(task)
            .build();
    }

    public static List<ResolvedTask> of(List<Task> tasks) {
        if (tasks == null) {
            return null;
        }

        return tasks
            .stream()
            .map(ResolvedTask::of)
            .toList();
    }

    public String uid() {
        return IdUtils.fromParts(this.task.getId(), this.value);
    }
}
