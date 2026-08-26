package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.flow.gen.flow.pojos.FlowsObject;
import org.flow.gen.flow.records.FlowsRecord;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.flow.gen.flow.Tables.FLOWS;

/**
 * Persistence mapping for both editable and deployed Flow states.
 */
public class FlowEntry extends FlowsObject {

    public static FlowEntry fromRecord(FlowsRecord record) {
        FlowEntry entry = new FlowEntry();
        entry.id = record.getId();
        entry.key = record.getKey();
        entry.companyId = record.getCompanyId();
        entry.reversion = record.getReversion();
        entry.draft = record.getDraft();
        entry.source = record.getSource();
        entry.lockVersion = record.getLockVersion();
        entry.description = record.getDescription();
        entry.status = record.getStatus();
        entry.creator = record.getCreator();
        entry.creatorId = record.getCreatorId();
        entry.updater = record.getUpdater();
        entry.updaterId = record.getUpdaterId();
        entry.deleter = record.getDeleter();
        entry.deleterId = record.getDeleterId();
        entry.createdAt = record.getCreatedAt();
        entry.updatedAt = record.getUpdatedAt();
        entry.deletedAt = record.getDeletedAt();
        entry.inputs = JsonObjects.Parse(record.get(FLOWS.INPUTS).data());
        entry.outputs = JsonObjects.Parse(record.get(FLOWS.OUTPUTS).data());
        entry.variables = JsonObject.Parse(record.get(FLOWS.VARIABLES).data());
        return entry;
    }

    public static FlowEntry fromDomain(Flow flow) {
        FlowEntry entry = new FlowEntry();
        entry.id = flow.id();
        entry.key = flow.key();
        entry.companyId = flow.companyId();
        entry.reversion = flow.versionOrNull();
        entry.draft = flow.draft();
        entry.source = flow.source();
        entry.lockVersion = flow.lockVersion();
        entry.description = flow.description();
        entry.status = AuditStatusCodec.encode(flow.status());
        entry.creator = ActorRefJsonCodec.encode(flow.creator());
        entry.updater = ActorRefJsonCodec.encode(flow.updater());
        entry.deleter = flow.deleter()
            .map(ActorRefJsonCodec::encode)
            .orElse(null);
        entry.createdAt = toOffsetDateTime(flow.createdAt());
        entry.updatedAt = toOffsetDateTime(flow.updatedAt());
        entry.deletedAt = flow.deletedAt()
            .map(FlowEntry::toOffsetDateTime)
            .orElse(null);
        entry.inputs = DataJsonCodec.encode(flow.inputs());
        entry.outputs = DataJsonCodec.encode(flow.outputs());
        entry.variables = JsonObject.FromMap(flow.variables());
        return entry;
    }

    public Flow toDomain(List<Task> tasks) {
        boolean editable = Boolean.TRUE.equals(draft);
        return Flow.rehydrate(
            id,
            companyId,
            key,
            editable,
            reversion,
            description,
            variables == null ? Map.of() : variables.asMap(),
            DataJsonCodec.decodeInputs(inputs, "Flow.inputs"),
            DataJsonCodec.decodeOutputs(outputs, "Flow.outputs"),
            editable ? List.of() : tasks,
            AuditStatusCodec.decode(status, "Flow.status"),
            ActorRefJsonCodec.decode(creator, "Flow.creator"),
            ActorRefJsonCodec.decode(updater, "Flow.updater"),
            ActorRefJsonCodec.decodeOptional(deleter, "Flow.deleter"),
            toEpochMillis(createdAt, "Flow.createdAt"),
            toEpochMillis(updatedAt, "Flow.updatedAt"),
            deletedAt == null
                ? null
                : deletedAt.toInstant().toEpochMilli(),
            source,
            lockVersion == null ? 0 : lockVersion
        );
    }

    static OffsetDateTime toOffsetDateTime(long epochMillis) {
        return OffsetDateTime.ofInstant(
            Instant.ofEpochMilli(epochMillis),
            ZoneOffset.UTC
        );
    }

    static long toEpochMillis(OffsetDateTime value, String field) {
        if (value == null) {
            throw new IllegalStateException(
                "Persisted " + field + " must not be null"
            );
        }
        return value.toInstant().toEpochMilli();
    }
}
