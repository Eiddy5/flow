package org.cses.flow.infrastructure.repositories.executions.codec;

import org.paas.json.JsonObject;

import java.util.Map;

public class TaskRunValuesJsonCodec {

    private TaskRunValuesJsonCodec() {
    }

    public static JsonObject encode(Map<String, Object> values) {
        return JsonObject.FromMap(values);
    }

    public static Map<String, Object> decode(JsonObject values) {
        return values == null ? Map.of() : values.asMap();
    }
}
