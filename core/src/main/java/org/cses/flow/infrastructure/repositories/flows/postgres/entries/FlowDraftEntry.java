package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import org.cses.flow.core.domains.flows.FlowDraft;
import org.flow.gen.flow.pojos.FlowDraftsObject;
import org.flow.gen.flow.records.FlowDraftsRecord;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class FlowDraftEntry extends FlowDraftsObject {

    public static FlowDraftEntry fromRecord(
        FlowDraftsRecord record
    ) {
        FlowDraftEntry entry = new FlowDraftEntry();
        entry.id = record.getId();
        entry.companyId = record.getCompanyId();
        entry.flowKey = record.getFlowKey();
        entry.deleted = record.getDeleted();
        entry.creator = record.getCreator();
        entry.creatorId = record.getCreatorId();
        entry.updater = record.getUpdater();
        entry.updaterId = record.getUpdaterId();
        entry.deleter = record.getDeleter();
        entry.deleterId = record.getDeleterId();
        entry.createdAt = record.getCreatedAt();
        entry.updatedAt = record.getUpdatedAt();
        entry.deletedAt = record.getDeletedAt();
        entry.raw = record.getRaw();
        entry.lockVersion = record.getLockVersion();
        return entry;
    }

    public static FlowDraftEntry fromDomain(
        FlowDraft draft
    ) {
        FlowDraftEntry entry = new FlowDraftEntry();
        entry.id = draft.id();
        entry.companyId = draft.companyId();
        entry.flowKey = draft.flowKey();
        entry.deleted = draft.isDeleted();
        entry.creator = ActorRefJsonCodec.encode(draft.creator());
        entry.updater = ActorRefJsonCodec.encode(draft.updater());
        entry.deleter = draft.deleter()
            .map(ActorRefJsonCodec::encode)
            .orElse(null);
        entry.createdAt = toOffsetDateTime(draft.createdAt());
        entry.updatedAt = toOffsetDateTime(draft.updatedAt());
        entry.deletedAt = draft.deletedAt()
            .map(FlowDraftEntry::toOffsetDateTime)
            .orElse(null);
        entry.raw = draft.raw();
        entry.lockVersion = draft.lockVersion();
        return entry;
    }

    public FlowDraft toDomain() {
        return FlowDraft.rehydrate(
            id,
            companyId,
            flowKey,
            raw,
            requiredBoolean(deleted, "FlowDraft.deleted"),
            ActorRefJsonCodec.decode(creator, "FlowDraft.creator"),
            ActorRefJsonCodec.decode(updater, "FlowDraft.updater"),
            ActorRefJsonCodec.decodeOptional(
                deleter,
                "FlowDraft.deleter"
            ),
            toEpochMillis(createdAt, "FlowDraft.createdAt"),
            toEpochMillis(updatedAt, "FlowDraft.updatedAt"),
            deletedAt == null
                ? null
                : deletedAt.toInstant().toEpochMilli(),
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

    private static boolean requiredBoolean(Boolean value, String field) {
        if (value == null) {
            throw new IllegalStateException(
                "Persisted " + field + " must not be null"
            );
        }
        return value;
    }
}
