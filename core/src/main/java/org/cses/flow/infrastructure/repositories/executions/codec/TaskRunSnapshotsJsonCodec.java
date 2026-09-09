package org.cses.flow.infrastructure.repositories.executions.codec;

import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.infrastructure.repositories.executions.entries.TaskRunEntry;
import org.jooq.JSONB;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stores inherited run snapshots without creating duplicate TaskRun table identities. */
public class TaskRunSnapshotsJsonCodec {

    /** Prevents creating instances of this stateless codec. */
    private TaskRunSnapshotsJsonCodec() {
    }

    /**
     * Serializes ordered snapshots through the existing TaskRun Entry mapping.
     * @param runs inherited snapshots, including an empty list for a root
     * @return JSONB array containing the exact runtime facts
     */
    public static JsonObjects encode(List<TaskRun> runs) {
        JsonObjects values = JsonObjects.Create();
        for (TaskRun run : runs) {
            TaskRunEntry entry = TaskRunEntry.from(null, run, values.size());
            values.add(JsonObject.Create()
                    .put("id", entry.id).put("taskId", entry.taskId)
                    .put("parentId", entry.parentId).put("iteration", entry.iteration)
                    .put("executionGenerationVersion", entry.executionGenerationVersion)
                    .put("generation", JsonObject.Parse(entry.generation.data()))
                    .put("state", JsonObject.Parse(entry.state.data()))
                    .put("inputs", entry.inputs).put("outputs", entry.outputs)
                    .put("error", entry.error));
        }
        return values;
    }

    /**
     * Restores ordered snapshots through the same Entry used for owned runs.
     * @param stored nonnull JSONB array from the current schema
     * @return independently restored TaskRuns
     * @throws IllegalArgumentException when the array or a snapshot is invalid
     */
    public static List<TaskRun> decode(JsonObjects stored) {
        Objects.requireNonNull(stored, "Inherited TaskRun snapshots");
        try {
            JsonObjects values = stored;
            List<TaskRun> runs = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                JsonObject value = values.getObject(index);
                var fields = value.asMap();
                TaskRunEntry entry = new TaskRunEntry();
                entry.id = (String) fields.get("id");
                entry.taskId = (String) fields.get("taskId");
                entry.parentId = (String) fields.get("parentId");
                entry.iteration = fields.get("iteration") == null ? null : value.getInt("iteration");
                entry.executionGenerationVersion = fields.get("executionGenerationVersion") == null
                        ? null : value.getInt("executionGenerationVersion");
                entry.generation = JSONB.valueOf(value.getObject("generation").toJson());
                entry.state = JSONB.valueOf(value.getObject("state").toJson());
                entry.inputs = Objects.requireNonNull(value.getObject("inputs"), "Snapshot inputs");
                entry.outputs = Objects.requireNonNull(value.getObject("outputs"), "Snapshot outputs");
                entry.error = (String) fields.get("error");
                runs.add(entry.to());
            }
            return List.copyOf(runs);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid inherited TaskRun snapshots", failure);
        }
    }
}
