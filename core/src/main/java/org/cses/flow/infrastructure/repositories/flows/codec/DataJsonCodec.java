package org.cses.flow.infrastructure.repositories.flows.codec;

import org.cses.flow.core.domains.flows.Data;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.jooq.JSONB;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.List;
import java.util.Map;

public class DataJsonCodec {

    private static Class<Input<?>> INPUT_TYPE = inputType();

    private DataJsonCodec() {
    }

    public static JsonObjects encode(List<? extends Data> values) {
        return JsonObjects.FromList(
            values.stream().map(DataJsonCodec::jsonValue).toList()
        );
    }

    public static JSONB encodeJsonb(List<? extends Data> values) {
        return JSONB.valueOf(encode(values).toJson());
    }

    public static List<Input<?>> decodeInputs(
        JsonObjects values,
        String field
    ) {
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
        List<Input<?>> inputs = normalized.asObjects(INPUT_TYPE);
        for (int index = 0; index < inputs.size(); index++) {
            try {
                inputs.get(index).validateDefinition();
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                    "Persisted " + field + "[" + index + "] is invalid",
                    exception
                );
            }
        }
        return List.copyOf(inputs);
    }

    public static List<Input<?>> decodeInputs(
        JSONB values,
        String field
    ) {
        return values == null
            ? List.of()
            : decodeInputs(JsonObjects.Parse(values.data()), field);
    }

    public static List<Output> decodeOutputs(
        JsonObjects values,
        String field
    ) {
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

    public static List<Output> decodeOutputs(
        JSONB values,
        String field
    ) {
        return values == null
            ? List.of()
            : decodeOutputs(JsonObjects.Parse(values.data()), field);
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
