package org.cses.flow.infrastructure.repositories.executions.entries;

import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.infrastructure.repositories.executions.codec.StateJsonCodec;
import org.cses.flow.infrastructure.repositories.executions.codec.TaskRunValuesJsonCodec;
import org.flow.gen.flow.pojos.TaskRunsObject;

public class TaskRunEntry extends TaskRunsObject {

    public static TaskRunEntry from(
        String executionId,
        TaskRun taskRun,
        int order
    ) {
        TaskRunEntry entry = new TaskRunEntry();
        entry.id = taskRun.id();
        entry.executionId = executionId;
        entry.taskId = taskRun.taskId();
        entry.parentId = taskRun.parentId().orElse(null);
        entry.iteration = taskRun.iteration().isPresent()
            ? taskRun.iteration().getAsInt()
            : null;
        entry.state = StateJsonCodec.encode(taskRun.state());
        entry.inputs = TaskRunValuesJsonCodec.encode(taskRun.inputs());
        entry.outputs = TaskRunValuesJsonCodec.encode(taskRun.outputs());
        entry.error = taskRun.error().orElse(null);
        entry.order = order;
        return entry;
    }

    public TaskRun to() {
        return TaskRun.rehydrate(
            id,
            taskId,
            parentId,
            iteration,
            TaskRunValuesJsonCodec.decode(inputs),
            StateJsonCodec.decode(state),
            TaskRunValuesJsonCodec.decode(outputs),
            error
        );
    }
}
