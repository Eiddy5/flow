package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.flow.gen.flow.pojos.FlowsObject;
import org.flow.gen.flow.records.FlowsRecord;
import org.paas.json.JsonObjects;
import java.util.List;

import static org.flow.gen.flow.Tables.FLOWS;

public final class FlowEntry extends FlowsObject {

    public static FlowEntry fromRecord(FlowsRecord record) {
        FlowEntry entry = new FlowEntry();
        entry.id = record.getId();
        entry.key = record.getKey();
        entry.companyId = record.getCompanyId();
        entry.deleted = record.getDeleted();
        entry.reversion = record.getReversion();
        entry.description = record.getDescription();
        entry.creator = record.getCreator();
        entry.creatorId = record.getCreatorId();
        entry.updater = record.getUpdater();
        entry.updaterId = record.getUpdaterId();
        entry.deleter = record.getDeleter();
        entry.deleterId = record.getDeleterId();
        entry.createdAt = record.getCreatedAt();
        entry.updatedAt = record.getUpdatedAt();
        entry.deletedAt = record.getDeletedAt();
        entry.inputs = JsonObjects.Parse(
            record.get(FLOWS.INPUTS).data()
        );
        entry.outputs = JsonObjects.Parse(
            record.get(FLOWS.OUTPUTS).data()
        );
        return entry;
    }

    public static FlowEntry fromDomain(Flow flow) {
        FlowEntry entry = new FlowEntry();
        entry.id = flow.id();
        entry.key = flow.key();
        entry.companyId = flow.companyId();
        entry.deleted = flow.isDeleted();
        entry.reversion = flow.reversion();
        entry.description = flow.description();
        entry.creator = ActorRefJsonCodec.encode(flow.creator());
        entry.updater = ActorRefJsonCodec.encode(flow.updater());
        entry.deleter = flow.deleter()
            .map(ActorRefJsonCodec::encode)
            .orElse(null);
        entry.createdAt = FlowDraftEntry.toOffsetDateTime(
            flow.createdAt()
        );
        entry.updatedAt = FlowDraftEntry.toOffsetDateTime(
            flow.updatedAt()
        );
        entry.deletedAt = flow.deletedAt()
            .map(FlowDraftEntry::toOffsetDateTime)
            .orElse(null);
        entry.inputs = DataJsonCodec.encode(flow.inputs());
        entry.outputs = DataJsonCodec.encode(flow.outputs());
        return entry;
    }

    public Flow toDomain(List<Task> tasks) {
        if (reversion == null) {
            throw new IllegalStateException(
                "Persisted Flow reversion must not be null"
            );
        }
        return Flow.rehydrate(
            id,
            companyId,
            key,
            reversion,
            description,
            DataJsonCodec.decodeInputs(inputs, "Flow.inputs"),
            DataJsonCodec.decodeOutputs(outputs, "Flow.outputs"),
            tasks,
            requiredBoolean(deleted, "Flow.deleted"),
            ActorRefJsonCodec.decode(creator, "Flow.creator"),
            ActorRefJsonCodec.decode(updater, "Flow.updater"),
            ActorRefJsonCodec.decodeOptional(deleter, "Flow.deleter"),
            FlowDraftEntry.toEpochMillis(
                createdAt,
                "Flow.createdAt"
            ),
            FlowDraftEntry.toEpochMillis(
                updatedAt,
                "Flow.updatedAt"
            ),
            deletedAt == null
                ? null
                : deletedAt.toInstant().toEpochMilli()
        );
    }

    private static boolean requiredBoolean(Boolean value, String field) {
        if (value == null) {
            throw new IllegalStateException(
                "Persisted " + field + " must not be null"
            );
        }
        return value;
    }
}
