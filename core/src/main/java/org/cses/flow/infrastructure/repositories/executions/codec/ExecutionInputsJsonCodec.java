package org.cses.flow.infrastructure.repositories.executions.codec;

import org.paas.json.JsonObject;

import java.util.Map;

public class ExecutionInputsJsonCodec {

    private ExecutionInputsJsonCodec() {
    }

    public static JsonObject encode(Map<String, Object> inputs) {
        return JsonObject.FromMap(inputs);
    }

    public static Map<String, Object> decode(JsonObject inputs) {
        return inputs == null ? Map.of() : inputs.asMap();
    }
}
