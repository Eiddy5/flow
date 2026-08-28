package org.cses.flow.infrastructure.repositories.flows.codec;

import org.jooq.JSONB;
import org.paas.json.JsonObject;

import java.util.Map;

public class FlowVariablesJsonCodec {

    private FlowVariablesJsonCodec() {
    }

    public static JsonObject encode(Map<String, Object> variables) {
        return JsonObject.FromMap(variables);
    }

    public static JSONB encodeJsonb(Map<String, Object> variables) {
        return JSONB.valueOf(encode(variables).toJson());
    }

    public static Map<String, Object> decode(JsonObject variables) {
        return variables == null ? Map.of() : variables.asMap();
    }

    public static Map<String, Object> decode(JSONB variables) {
        return variables == null
            ? Map.of()
            : decode(JsonObject.Parse(variables.data()));
    }
}
