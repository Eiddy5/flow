package org.cses.flow.infrastructure.repositories.executions.postgres.entries;

import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.State;
import org.flow.gen.flow.pojos.TaskRunsObject;
import org.flow.gen.flow.records.TaskRunsRecord;
import org.paas.json.JsonObject;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.flow.gen.flow.Tables.TASK_RUNS;

public final class TaskRunEntry extends TaskRunsObject {

    public static TaskRunEntry fromRecord(TaskRunsRecord record) {
        TaskRunEntry entry = new TaskRunEntry();
        entry.id = record.getId();
        entry.executionId = record.getExecutionId();
        entry.taskId = record.getTaskId();
        entry.parentId = record.getParentId();
        entry.iteration = record.getIteration();
        entry.state = record.getState();
        entry.startAt = record.getStartAt();
        entry.endAt = record.getEndAt();
        entry.inputs = JsonObject.Parse(
            record.get(TASK_RUNS.INPUTS).data()
        );
        entry.outputs = JsonObject.Parse(
            record.get(TASK_RUNS.OUTPUTS).data()
        );
        entry.error = record.getError();
        entry.order = record.getOrder();
        entry.createdAt = record.getCreatedAt();
        entry.updatedAt = record.getUpdatedAt();
        entry.deletedAt = record.getDeletedAt();
        return entry;
    }

    public static TaskRunEntry fromDomain(
        String executionId,
        TaskRun taskRun,
        int order,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
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
        entry.createdAt = createdAt;
        entry.updatedAt = updatedAt;
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
