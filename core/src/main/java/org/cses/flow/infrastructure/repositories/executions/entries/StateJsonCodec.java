package org.cses.flow.infrastructure.repositories.executions.entries;

import org.cses.flow.core.domains.flows.State;
import org.jooq.JSONB;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.ArrayList;
import java.util.List;

final class StateJsonCodec {

    private StateJsonCodec() {
    }

    static JSONB encode(State state) {
        JsonObjects history = JsonObjects.Create();
        for (State.History item : state.history()) {
            history.add(JsonObject.Create()
                .put("state", item.state().name())
                .put("date", item.date()));
        }
        JsonObject value = JsonObject.Create()
            .put("current", state.current().name())
            .put("history", history);
        return JSONB.valueOf(value.toJson());
    }

    static State decode(JSONB storedState) {
        if (storedState == null) {
            throw new IllegalArgumentException(
                "Persisted state must not be null"
            );
        }
        try {
            JsonObject value = JsonObject.Parse(storedState.data());
            if (!value.has("current") || !value.has("history")) {
                throw new IllegalArgumentException(
                    "Persisted state must contain current and history"
                );
            }
            String current = value.getString("current");
            if (current == null || current.isBlank()) {
                throw new IllegalArgumentException(
                    "Persisted current state must not be blank"
                );
            }
            JsonObjects values = value.getObjects("history");
            if (values == null) {
                throw new IllegalArgumentException(
                    "Persisted state history must be an array"
                );
            }
            List<State.History> history = new ArrayList<>(values.size());
            for (int index = 0; index < values.size(); index++) {
                JsonObject item = values.getObject(index);
                if (!item.has("state") || !item.has("date")) {
                    throw new IllegalArgumentException(
                        "State history entry must contain state and date"
                    );
                }
                history.add(State.History.rehydrate(
                    State.Type.valueOf(item.getString("state")),
                    item.getLong("date")
                ));
            }
            return State.rehydrate(
                State.Type.valueOf(current),
                history
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                "Persisted state is invalid",
                exception
            );
        }
    }
}
