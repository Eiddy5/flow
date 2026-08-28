package org.cses.flow.infrastructure.repositories.flows.entries;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.flow.gen.flow.pojos.FlowsObject;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.List;
import java.util.Map;

/**
 * Persistence mapping for both editable and deployed Flow states.
 */
public class FlowEntry extends FlowsObject {

    public static FlowEntry fromDomain(Flow flow) {
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
        entry.inputs = JsonObjects.FromList(flow.inputs());
        entry.outputs = JsonObjects.FromList(flow.outputs());
        entry.variables = JsonObject.FromMap(flow.variables());
        return entry;
    }

    public Flow toDomain() {
        return toDomain(List.of());
    }

    public Flow toDomain(List<Task> tasks) {
        boolean editable = Boolean.TRUE.equals(draft);
        return Flow.rehydrate(
            id,
            companyId,
            key,
            editable,
            version,
            description,
            variables == null ? Map.of() : variables.asMap(),
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
