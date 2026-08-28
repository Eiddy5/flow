package org.cses.flow.infrastructure.repositories.flows.entries;

import org.cses.flow.core.domains.ActorRef;
import org.jooq.JSONB;
import org.paas.json.JsonObject;

public class ActorRefJsonCodec {

    private ActorRefJsonCodec() {
    }

    public static JSONB encode(ActorRef actor) {
        if (actor == null) {
            return null;
        }
        return JSONB.valueOf(JsonObject.From(actor).toJson());
    }

    public static ActorRef decode(JSONB value, String field) {
        if (value == null) {
            throw new IllegalStateException(
                "Persisted " + field + " must not be null"
            );
        }
        try {
            ActorRef restored = JsonObject.Parse(value.data())
                .asObject(ActorRef.class);
            return ActorRef.rehydrate(
                restored.id(),
                restored.name().orElse(null)
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                "Persisted " + field + " must contain a valid actor",
                exception
            );
        }
    }

    public static ActorRef decodeOptional(JSONB value, String field) {
        return value == null ? null : decode(value, field);
    }
}
