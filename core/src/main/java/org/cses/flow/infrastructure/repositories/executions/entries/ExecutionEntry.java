package org.cses.flow.infrastructure.repositories.executions.entries;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.Origin;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.infrastructure.repositories.executions.codec.ExecutionInputsJsonCodec;
import org.cses.flow.infrastructure.repositories.executions.codec.GenerationJsonCodec;
import org.cses.flow.infrastructure.repositories.executions.codec.StateJsonCodec;
import org.cses.flow.infrastructure.repositories.executions.codec.TaskRunSnapshotsJsonCodec;
import org.cses.flow.infrastructure.repositories.flows.codec.ActorRefJsonCodec;
import org.flow.gen.flow.pojos.ExecutionsObject;

import java.util.List;

public class ExecutionEntry extends ExecutionsObject {

    /**
     * Maps the complete Execution and inherited snapshots to the current database shape.
     * The repository separately manages the technical lock version.
     * @param execution validated domain snapshot
     * @return new database Entry without generating domain facts
     */
    public static ExecutionEntry from(Execution execution) {
        ExecutionEntry entry = new ExecutionEntry();
        entry.id = execution.id();
        entry.companyId = execution.companyId();
        entry.flowKey = execution.flowKey();
        entry.flowVersion = execution.flowVersion();
        entry.parentId = execution.origin().parentId();
        entry.originId = execution.origin().originId();
        entry.inheritedTaskRuns = TaskRunSnapshotsJsonCodec.encode(execution.inheritedTaskRuns());
        entry.inputs = ExecutionInputsJsonCodec.encode(execution.inputs());
        entry.state = StateJsonCodec.encode(execution.state());
        entry.generation = GenerationJsonCodec.encode(execution.generation());
        entry.creator = ActorRefJsonCodec.encode(execution.creator());
        entry.createdAt = execution.createdAt();
        return entry;
    }

    /**
     * Combines persisted inherited snapshots with this instance's ordered owned runs.
     * @param taskRuns independently decoded owned TaskRun rows, in order
     * @return fully validated Execution including its source relationship
     * @throws IllegalArgumentException when origin or inherited history is invalid
     * @throws IllegalStateException when the bound Flow version is missing
     */
    public Execution to(List<TaskRun> taskRuns) {
        if (flowVersion == null) {
            throw new IllegalStateException(
                "Persisted Execution flowVersion must not be null"
            );
        }
        return Execution.rehydrate(
            id,
            companyId,
            ActorRefJsonCodec.decode(creator, "Execution.creator"),
            requiredEpochMillis(createdAt, "Execution.createdAt"),
            flowKey,
            flowVersion,
            ExecutionInputsJsonCodec.decode(inputs),
            GenerationJsonCodec.decode(generation),
            StateJsonCodec.decode(state),
            taskRuns,
            Origin.create(parentId, originId),
            TaskRunSnapshotsJsonCodec.decode(inheritedTaskRuns)
        );
    }

    private static long requiredEpochMillis(
        Long value,
        String field
    ) {
        if (value == null) {
            throw new IllegalStateException(
                "Persisted " + field + " must not be null"
            );
        }
        return value;
    }
}
