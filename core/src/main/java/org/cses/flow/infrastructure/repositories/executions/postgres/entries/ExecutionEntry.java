package org.cses.flow.infrastructure.repositories.executions.postgres.entries;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.State;
import org.flow.gen.flow.pojos.ExecutionsObject;
import org.flow.gen.flow.records.ExecutionsRecord;
import org.jooq.JSONB;

import java.time.OffsetDateTime;
import java.util.List;

public final class ExecutionEntry extends ExecutionsObject {

    public static ExecutionEntry fromRecord(ExecutionsRecord record) {
        ExecutionEntry entry = new ExecutionEntry();
        entry.id = record.getId();
        entry.companyId = record.getCompanyId();
        entry.flowId = record.getFlowId();
        entry.flowReversion = record.getFlowReversion();
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
        entry.flowId = execution.flowId();
        entry.flowReversion = execution.flowReversion();
        entry.state = StateJsonCodec.encode(execution.state());
        entry.lockVersion = execution.lockVersion();
        entry.creator = creator;
        entry.updater = updater;
        entry.createdAt = createdAt;
        entry.updatedAt = updatedAt;
        return entry;
    }

    public Execution toDomain(List<TaskRun> taskRuns) {
        if (flowReversion == null) {
            throw new IllegalStateException(
                "Persisted Execution flowReversion must not be null"
            );
        }
        return Execution.rehydrate(
            id,
            companyId,
            flowId,
            flowReversion,
            StateJsonCodec.decode(state),
            lockVersion == null ? 0 : lockVersion,
            taskRuns
        );
    }
}
