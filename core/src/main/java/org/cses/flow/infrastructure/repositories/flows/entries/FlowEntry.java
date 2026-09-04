package org.cses.flow.infrastructure.repositories.flows.entries;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.infrastructure.repositories.flows.codec.ActorRefJsonCodec;
import org.cses.flow.infrastructure.repositories.flows.codec.AuditStatusCodec;
import org.cses.flow.infrastructure.repositories.flows.codec.DataJsonCodec;
import org.cses.flow.infrastructure.repositories.flows.codec.FlowVariablesJsonCodec;
import org.flow.gen.flow.pojos.FlowsObject;

import java.util.List;

/**
 * Persistence mapping for both editable and deployed Flow states.
 */
public class FlowEntry extends FlowsObject {

    /**
     * Maps a Flow to one versioned database row.
     *
     * @param flow non-null definition and domain-owned audit facts to read;
     *        the object and its collections are not modified
     * @param rowId non-blank technical identifier for the new database row
     * @param version positive Repository-assigned Flow version
     * @return a new persistence entry with converted values independent of
     *         mutable containers owned by the supplied Flow
     * @throws IllegalArgumentException when a value cannot be encoded
     * @throws NullPointerException when {@code flow} is {@code null}
     */
    public static FlowEntry from(
            Flow flow,
            String rowId,
            long version
    ) {
        FlowEntry entry = new FlowEntry();
        entry.id = rowId;
        entry.key = flow.key();
        entry.companyId = flow.companyId();
        entry.version = version;
        entry.draft = flow.draft();
        entry.source = flow.source();
        entry.description = flow.description();
        entry.status = AuditStatusCodec.encode(flow.status());
        entry.creator = ActorRefJsonCodec.encode(flow.creator());
        entry.updater = ActorRefJsonCodec.encode(flow.updater());
        entry.deleter = flow.deleter()
            .map(ActorRefJsonCodec::encode)
            .orElse(null);
        entry.createdAt = flow.createdAt();
        entry.updatedAt = flow.updatedAt();
        entry.deletedAt = flow.deletedAt()
            .orElse(null);
        entry.inputs = DataJsonCodec.encodeJsonb(flow.inputs());
        entry.outputs = DataJsonCodec.encodeJsonb(flow.outputs());
        entry.variables = FlowVariablesJsonCodec.encodeJsonb(flow.variables());
        return entry;
    }

    /**
     * Restores a Flow without Task rows.
     *
     * @return a detached Flow containing this entry's persisted state
     */
    public Flow to() {
        return to(List.of());
    }

    /**
     * Restores a Flow with the supplied Task snapshot.
     *
     * @param tasks Task snapshot associated with this Flow version;
     *        {@code null} becomes an empty immutable list
     * @return a detached Flow containing persisted entry facts and a shallow
     *         immutable copy of the Task list; Task elements remain shared
     * @throws IllegalArgumentException when persisted values are invalid
     * @throws IllegalStateException when a required timestamp is absent
     * @throws NullPointerException when {@code tasks} contains a null element
     */
    public Flow to(List<Task> tasks) {
        return Flow.rehydrate(
            id,
            companyId,
            key,
            Boolean.TRUE.equals(draft),
            version,
            description,
            FlowVariablesJsonCodec.decode(variables),
            DataJsonCodec.decodeInputs(inputs, "Flow.inputs"),
            DataJsonCodec.decodeOutputs(outputs, "Flow.outputs"),
            tasks,
            AuditStatusCodec.decode(status, "Flow.status"),
            ActorRefJsonCodec.decode(creator, "Flow.creator"),
            ActorRefJsonCodec.decode(updater, "Flow.updater"),
            ActorRefJsonCodec.decodeOptional(deleter, "Flow.deleter"),
            requiredEpochMillis(createdAt, "Flow.createdAt"),
            requiredEpochMillis(updatedAt, "Flow.updatedAt"),
            deletedAt == null
                ? null
                : deletedAt,
            source
        );
    }

    static long requiredEpochMillis(Long value, String field) {
        if (value == null) {
            throw new IllegalStateException(
                "Persisted " + field + " must not be null"
            );
        }
        return value;
    }
}
