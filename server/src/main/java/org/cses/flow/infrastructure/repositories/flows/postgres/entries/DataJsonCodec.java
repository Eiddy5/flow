package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import org.cses.flow.core.domains.flows.Data;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.List;
import java.util.Map;

final class DataJsonCodec {

    private static final Class<Input<?>> INPUT_TYPE = inputType();

    private DataJsonCodec() {
    }

    static JsonObjects encode(List<? extends Data> values) {
        return JsonObjects.FromList(
            values.stream().map(DataJsonCodec::jsonValue).toList()
        );
    }

    static List<Input<?>> decodeInputs(JsonObjects values, String field) {
        if (values == null) {
            return List.of();
        }
        JsonObjects normalized = JsonObjects.Create();
        for (int index = 0; index < values.size(); index++) {
            JsonObject definition = JsonObject.FromMap(
                values.getObject(index).asMap()
            );
            String path = "Persisted " + field + "[" + index + "]";
            Map<String, Object> source = definition.asMap();
            String key = requiredText(source, "key", path);
            String type = requiredText(source, "type", path);
            DataType dataType = DataType.parse(type);
            definition.replace("type", dataType.name());
            Object defaultValue = source.get("defaultValue");
            if (defaultValue != null) {
                definition.replace(
                    "defaultValue",
                    dataType.normalize(defaultValue)
                );
            }
            if (!source.containsKey("displayName")) {
                definition.put("displayName", key);
            }
            if (!source.containsKey("required")) {
                definition.put("required", false);
            }
            normalized.add(definition);
        }
        return List.copyOf(normalized.asObjects(INPUT_TYPE));
    }

    static List<Output> decodeOutputs(JsonObjects values, String field) {
        if (values == null) {
            return List.of();
        }
        return values.toListMap().stream()
            .map(value -> Output.rehydrate(
                requiredText(value, "key", field),
                DataType.parse(requiredText(value, "type", field))
            ))
            .toList();
    }

    private static Object jsonValue(Data data) {
        if (data instanceof Input<?> input) {
            JsonObject value = JsonObject.From(input);
            if (input.getDefaultValue() instanceof Character character) {
                value.replace("defaultValue", character.toString());
            }
            return value;
        }
        return Map.of(
            "key",
            data.getKey(),
            "type",
            data.getType().name()
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

    @SuppressWarnings("unchecked")
    private static Class<Input<?>> inputType() {
        return (Class<Input<?>>) (Class<?>) Input.class;
    }
}
