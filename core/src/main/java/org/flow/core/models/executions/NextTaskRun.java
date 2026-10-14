package org.flow.core.models.executions;

import org.flow.core.models.tasks.Task;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor
public class NextTaskRun {
    @NotNull
    TaskRun taskRun;

    @NotNull
    Task task;
}
