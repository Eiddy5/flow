package org.cses.flow.infrastructure.repositories.executions.codec;

import org.cses.flow.core.domains.executions.Generation;
import org.jooq.JSONB;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact JSONB mapping for Generation Current and History.
 */
public final class GenerationJsonCodec {

    private GenerationJsonCodec() {
    }

    public static JSONB encode(Generation generation) {
        JsonObjects history = JsonObjects.Create();
        for (Generation.Current current : generation.history().currents()) {
            history.add(encodeCurrent(current));
        }
        JsonObject value = JsonObject.Create()
                .put("current", generation.current()
                        .map(GenerationJsonCodec::encodeCurrent)
                        .orElse(null))
                .put("history", history);
        return JSONB.valueOf(value.toJson());
    }

    public static Generation decode(JSONB storedGeneration) {
        if (storedGeneration == null) {
            throw new IllegalArgumentException(
                    "Persisted Generation must not be null"
            );
        }
        try {
            JsonObject value = JsonObject.Parse(storedGeneration.data());
            JsonObjects historyValues = value.getObjects("history");
            if (historyValues == null) {
                throw new IllegalArgumentException(
                        "Persisted Generation history must be an array"
                );
            }
            List<Generation.Current> history = new ArrayList<>(
                    historyValues.size()
            );
            for (int index = 0; index < historyValues.size(); index++) {
                history.add(decodeCurrent(historyValues.getObject(index)));
            }
            JsonObject currentValue = value.getObject("current");
            Generation.Current current = currentValue == null
                    ? null
                    : decodeCurrent(currentValue);
            return Generation.rehydrate(
                    current,
                    Generation.History.rehydrate(history)
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Persisted Generation is invalid",
                    exception
            );
        }
    }

    private static JsonObject encodeCurrent(Generation.Current current) {
        JsonObject value = JsonObject.Create()
                .put("version", current.version())
                .put("reason", current.reason())
                .put("date", current.date());
        current.sourceTaskRunId().ifPresent(id ->
                value.put("sourceTaskRunId", id)
        );
        current.targetTaskRunId().ifPresent(id ->
                value.put("targetTaskRunId", id)
        );
        return value;
    }

    private static Generation.Current decodeCurrent(JsonObject value) {
        if (value == null
                || !value.has("version")
                || !value.has("reason")
                || !value.has("date")) {
            throw new IllegalArgumentException(
                    "Generation Current requires version, reason and date"
            );
        }
        return Generation.Current.rehydrate(
                value.getInt("version"),
                value.has("sourceTaskRunId")
                        ? value.getString("sourceTaskRunId")
                        : null,
                value.has("targetTaskRunId")
                        ? value.getString("targetTaskRunId")
                        : null,
                value.getString("reason"),
                value.getLong("date")
        );
    }
}
