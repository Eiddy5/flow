package org.cses.flow.infrastructure.repositories.externaltasks.postgres.entries;

import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.flow.gen.flow.pojos.ExternalTaskObject;
import org.flow.gen.flow.records.ExternalTaskRecord;
import org.paas.json.JsonObject;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.flow.gen.flow.Tables.EXTERNAL_TASK;

public final class ExternalTaskEntry extends ExternalTaskObject {

    public static ExternalTaskEntry fromRecord(ExternalTaskRecord record) {
        ExternalTaskEntry entry = new ExternalTaskEntry();
        entry.companyId = record.getCompanyId();
        entry.id = record.getId();
        entry.executionId = record.getExecutionId();
        entry.taskRunId = record.getTaskRunId();
        entry.status = record.getStatus();
        entry.outputs = JsonObject.Parse(
            record.get(EXTERNAL_TASK.OUTPUTS).data()
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
        entry.status = task.status().name();
        entry.outputs = JsonObject.FromMap(task.outputs());
        entry.lockVersion = task.lockVersion();
        entry.createdAt = createdAt;
        entry.updatedAt = updatedAt;
        return entry;
    }

    public ExternalTask toDomain() {
        Map<String, Object> restoredOutputs = outputs == null
            ? Map.of()
            : outputs.asMap();
        return ExternalTask.rehydrate(
            id,
            companyId,
            executionId,
            taskRunId,
            ExternalTaskStatus.valueOf(status),
            restoredOutputs,
            lockVersion == null ? 0 : lockVersion
        );
    }
}
