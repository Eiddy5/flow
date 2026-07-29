package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import org.cses.flow.core.domains.flows.Data;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.paas.json.JsonObjects;

import java.util.List;
import java.util.Map;

final class DataJsonCodec {

    private DataJsonCodec() {
    }

    static JsonObjects encode(List<? extends Data> values) {
        return JsonObjects.FromList(
            values.stream().map(DataJsonCodec::dataMap).toList()
        );
    }

    static List<Input> decodeInputs(JsonObjects values, String field) {
        if (values == null) {
            return List.of();
        }
        return values.toListMap().stream()
            .map(value -> Input.rehydrate(
                requiredText(value, "key", field),
                requiredText(value, "type", field)
            ))
            .toList();
    }

    static List<Output> decodeOutputs(JsonObjects values, String field) {
        if (values == null) {
            return List.of();
        }
        return values.toListMap().stream()
            .map(value -> Output.rehydrate(
                requiredText(value, "key", field),
                requiredText(value, "type", field)
            ))
            .toList();
    }

    private static Map<String, Object> dataMap(Data data) {
        return Map.of(
            "key",
            data.getKey(),
            "type",
            data.getType()
        );
    }

    private static String requiredText(
        Map<String, Object> value,
        String property,
        String field
    ) {
        Object candidate = value.get(property);
        if (!(candidate instanceof String text) || text.isBlank()) {
            throw new IllegalStateException(
                "Persisted " + field + "." + property
                    + " must be non-blank text"
            );
        }
        return text.trim();
    }
}
