package org.cses.flow.infrastructure.repositories.executions.entries;

import org.cses.flow.core.domains.executions.TaskRun;
import org.flow.gen.flow.pojos.TaskRunsObject;
import org.paas.json.JsonObject;

import java.util.Map;

public final class TaskRunEntry extends TaskRunsObject {

    public static TaskRunEntry fromDomain(
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
        entry.inputs = JsonObject.FromMap(taskRun.inputs());
        entry.outputs = JsonObject.FromMap(taskRun.outputs());
        entry.error = taskRun.error().orElse(null);
        entry.order = order;
        return entry;
    }

    public TaskRun toDomain() {
        Map<String, Object> restoredInputs = inputs == null
            ? Map.of()
            : inputs.asMap();
        Map<String, Object> restoredOutputs = outputs == null
            ? Map.of()
            : outputs.asMap();
        return TaskRun.rehydrate(
            id,
            taskId,
            parentId,
            iteration,
            restoredInputs,
            StateJsonCodec.decode(state),
            restoredOutputs,
            error
        );
    }
}
