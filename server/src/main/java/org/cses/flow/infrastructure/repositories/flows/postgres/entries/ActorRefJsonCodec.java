package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import org.cses.flow.core.domains.flows.ActorRef;
import org.jooq.JSONB;
import org.paas.json.JsonObject;

import java.util.Map;

final class ActorRefJsonCodec {

    private ActorRefJsonCodec() {
    }

    static JSONB encode(ActorRef actor) {
        if (actor == null) {
            return null;
        }
        JsonObject value = JsonObject.Create("id", actor.id());
        actor.name().ifPresent(name -> value.put("name", name));
        return JSONB.valueOf(value.toJson());
    }

    static ActorRef decode(JSONB value, String field) {
        if (value == null) {
            throw new IllegalStateException(
                "Persisted " + field + " must not be null"
            );
        }
        Map<String, Object> actor;
        try {
            actor = JsonObject.Parse(value.data()).asMap();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                "Persisted " + field + " must be a JSON object",
                exception
            );
        }
        Object id = actor.get("id");
        if (!(id instanceof String actorId) || actorId.isBlank()) {
            throw new IllegalStateException(
                "Persisted " + field + ".id must be non-blank text"
            );
        }
        Object name = actor.get("name");
        if (name != null && !(name instanceof String)) {
            throw new IllegalStateException(
                "Persisted " + field + ".name must be text"
            );
        }
        return ActorRef.rehydrate(actorId, (String) name);
    }

    static ActorRef decodeOptional(JSONB value, String field) {
        return value == null ? null : decode(value, field);
    }
}
