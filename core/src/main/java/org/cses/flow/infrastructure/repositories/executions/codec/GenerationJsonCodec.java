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
public class GenerationJsonCodec {

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

    /**
     * Serializes one generation including explicit invalidations.
     *
     * @param current validated generation record to read
     * @return new JSON object; loop rounds have an explicit empty affected set
     */
    private static JsonObject encodeCurrent(Generation.Current current) {
        JsonObject value = JsonObject.Create()
                .put("version", current.version())
                .put("reason", current.reason())
                .put("date", current.date())
                .put("affectedTaskRunIds", current.affectedTaskRunIds());
        current.sourceTaskRunId().ifPresent(id ->
                value.put("sourceTaskRunId", id)
        );
        current.targetTaskRunId().ifPresent(id ->
                value.put("targetTaskRunId", id)
        );
        return value;
    }

    /**
     * Restores the required explicit invalidations of the current storage contract.
     *
     * @param value persisted generation JSON object to read
     * @return new validated generation record
     * @throws IllegalArgumentException when required coordinates or generation values are invalid
     */
    private static Generation.Current decodeCurrent(JsonObject value) {
        if (value == null
                || !value.has("version")
                || !value.has("reason")
                || !value.has("date")
                || !value.has("affectedTaskRunIds")) {
            throw new IllegalArgumentException(
                    "Generation Current requires version, reason, date and affectedTaskRunIds"
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
                value.getLong("date"),
                value.getStrings("affectedTaskRunIds")
        );
    }
}
