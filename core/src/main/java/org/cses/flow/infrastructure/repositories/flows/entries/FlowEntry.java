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

    public static FlowEntry from(Flow flow) {
        FlowEntry entry = new FlowEntry();
        entry.id = flow.id();
        entry.key = flow.key();
        entry.companyId = flow.companyId();
        entry.version = flow.versionOrNull();
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

    public Flow to() {
        return to(List.of());
    }

    public Flow to(List<Task> tasks) {
        boolean editable = Boolean.TRUE.equals(draft);
        return Flow.rehydrate(
            id,
            companyId,
            key,
            editable,
            version,
            description,
            FlowVariablesJsonCodec.decode(variables),
            DataJsonCodec.decodeInputs(inputs, "Flow.inputs"),
            DataJsonCodec.decodeOutputs(outputs, "Flow.outputs"),
            editable ? List.of() : tasks,
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
