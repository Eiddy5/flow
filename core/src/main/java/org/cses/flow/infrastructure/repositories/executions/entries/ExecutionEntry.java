package org.cses.flow.infrastructure.repositories.executions.entries;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.infrastructure.repositories.flows.entries.ActorRefJsonCodec;
import org.flow.gen.flow.pojos.ExecutionsObject;
import org.paas.json.JsonObject;

import java.util.List;

public final class ExecutionEntry extends ExecutionsObject {

    public static ExecutionEntry fromDomain(Execution execution) {
        ExecutionEntry entry = new ExecutionEntry();
        entry.id = execution.id();
        entry.companyId = execution.companyId();
        entry.flowKey = execution.flowKey();
        entry.flowVersion = execution.flowVersion();
        entry.inputs = JsonObject.FromMap(execution.inputs());
        entry.state = StateJsonCodec.encode(execution.state());
        entry.creator = ActorRefJsonCodec.encode(execution.creator());
        entry.createdAt = execution.createdAt();
        return entry;
    }

    public Execution toDomain(List<TaskRun> taskRuns) {
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
            inputs == null ? java.util.Map.of() : inputs.asMap(),
            StateJsonCodec.decode(state),
            taskRuns
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
