package org.cses.flow.infrastructure.repositories.externaltasks.postgres.entries;

import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.flow.gen.flow.pojos.AssignmentObject;
import org.flow.gen.flow.records.AssignmentRecord;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import static org.flow.gen.flow.Tables.ASSIGNMENT;

public final class ExternalTaskEntry extends AssignmentObject {

    public static ExternalTaskEntry fromRecord(AssignmentRecord record) {
        ExternalTaskEntry entry = new ExternalTaskEntry();
        entry.companyId = record.getCompanyId();
        entry.id = record.getId();
        entry.executionId = record.getExecutionId();
        entry.taskRunId = record.getTaskRunId();
        entry.allowedOutputs = JsonObjects.Parse(
            record.get(ASSIGNMENT.ALLOWED_OUTPUTS).data()
        );
        entry.status = record.getStatus();
        entry.outputs = JsonObject.Parse(
            record.get(ASSIGNMENT.OUTPUTS).data()
        );
        entry.lockVersion = record.getLockVersion();
        entry.createdAt = record.getCreatedAt();
        entry.updatedAt = record.getUpdatedAt();
        return entry;
    }

    public static ExternalTaskEntry fromDomain(
        ExternalTask task,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        ExternalTaskEntry entry = new ExternalTaskEntry();
        entry.companyId = task.companyId();
        entry.id = task.id();
        entry.executionId = task.executionId();
        entry.taskRunId = task.taskRunId();
        entry.allowedOutputs = JsonObjects.FromSet(task.allowedOutputs());
        entry.status = task.status().name();
        entry.outputs = JsonObject.FromMap(task.outputs());
        entry.lockVersion = task.lockVersion();
        entry.createdAt = createdAt;
        entry.updatedAt = updatedAt;
        return entry;
    }

    public ExternalTask toDomain() {
        Set<String> restoredAllowedOutputs = allowedOutputs == null
            ? Set.of()
            : Set.copyOf(allowedOutputs.asStrings());
        Map<String, Object> restoredOutputs = outputs == null
            ? Map.of()
            : outputs.asMap();
        return ExternalTask.rehydrate(
            id,
            companyId,
            executionId,
            taskRunId,
            restoredAllowedOutputs,
            ExternalTaskStatus.valueOf(status),
            restoredOutputs,
            lockVersion == null ? 0 : lockVersion
        );
    }
}
