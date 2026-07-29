package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import org.cses.flow.core.domains.flows.FlowWithSource;
import org.flow.gen.flow.pojos.FlowDraftsObject;
import org.flow.gen.flow.records.FlowDraftsRecord;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class FlowWithSourceEntry extends FlowDraftsObject {

    public static FlowWithSourceEntry fromRecord(
        FlowDraftsRecord record
    ) {
        FlowWithSourceEntry entry = new FlowWithSourceEntry();
        entry.id = record.getId();
        entry.companyId = record.getCompanyId();
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

    public static FlowWithSourceEntry fromDomain(
        FlowWithSource source
    ) {
        FlowWithSourceEntry entry = new FlowWithSourceEntry();
        entry.id = source.id();
        entry.companyId = source.companyId();
        entry.creator = ActorRefJsonCodec.encode(source.creator());
        entry.updater = ActorRefJsonCodec.encode(source.updater());
        entry.deleter = source.deleter()
            .map(ActorRefJsonCodec::encode)
            .orElse(null);
        entry.createdAt = toOffsetDateTime(source.createdAt());
        entry.updatedAt = toOffsetDateTime(source.updatedAt());
        entry.deletedAt = source.deletedAt()
            .map(FlowWithSourceEntry::toOffsetDateTime)
            .orElse(null);
        entry.raw = source.raw();
        entry.lockVersion = source.lockVersion();
        return entry;
    }

    public FlowWithSource toDomain() {
        return FlowWithSource.rehydrate(
            id,
            companyId,
            raw,
            ActorRefJsonCodec.decode(creator, "FlowWithSource.creator"),
            ActorRefJsonCodec.decode(updater, "FlowWithSource.updater"),
            ActorRefJsonCodec.decodeOptional(
                deleter,
                "FlowWithSource.deleter"
            ),
            toEpochMillis(createdAt, "FlowWithSource.createdAt"),
            toEpochMillis(updatedAt, "FlowWithSource.updatedAt"),
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
}
