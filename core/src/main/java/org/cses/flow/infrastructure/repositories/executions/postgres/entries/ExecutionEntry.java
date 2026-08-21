package org.cses.flow.infrastructure.repositories.executions.postgres.entries;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.State;
import org.flow.gen.flow.pojos.ExecutionsObject;
import org.flow.gen.flow.records.ExecutionsRecord;
import org.jooq.JSONB;
import org.paas.json.JsonObject;

import java.time.OffsetDateTime;
import java.util.List;

import static org.flow.gen.flow.Tables.EXECUTIONS;

public final class ExecutionEntry extends ExecutionsObject {

    public static ExecutionEntry fromRecord(ExecutionsRecord record) {
        ExecutionEntry entry = new ExecutionEntry();
        entry.id = record.getId();
        entry.companyId = record.getCompanyId();
        entry.flowKey = record.getFlowKey();
        entry.flowVersion = record.getFlowVersion();
        org.jooq.JSONB storedInputs = record.get(EXECUTIONS.INPUTS);
        entry.inputs = storedInputs == null
            ? null
            : JsonObject.Parse(storedInputs.data());
        entry.state = record.getState();
        entry.lockVersion = record.getLockVersion();
        entry.creator = record.getCreator();
        entry.updater = record.getUpdater();
        entry.deleter = record.getDeleter();
        entry.createdAt = record.getCreatedAt();
        entry.updatedAt = record.getUpdatedAt();
        entry.deletedAt = record.getDeletedAt();
        return entry;
    }

    public static ExecutionEntry fromDomain(
        Execution execution,
        JSONB creator,
        JSONB updater,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        ExecutionEntry entry = new ExecutionEntry();
        entry.id = execution.id();
        entry.companyId = execution.companyId();
        entry.flowKey = execution.flowKey();
        entry.flowVersion = execution.flowVersion();
        entry.inputs = JsonObject.FromMap(execution.inputs());
        entry.state = StateJsonCodec.encode(execution.state());
        entry.lockVersion = execution.lockVersion();
        entry.creator = creator;
        entry.updater = updater;
        entry.createdAt = createdAt;
        entry.updatedAt = updatedAt;
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
            flowKey,
            flowVersion,
            inputs == null ? java.util.Map.of() : inputs.asMap(),
            StateJsonCodec.decode(state),
            lockVersion == null ? 0 : lockVersion,
            taskRuns
        );
    }
}
