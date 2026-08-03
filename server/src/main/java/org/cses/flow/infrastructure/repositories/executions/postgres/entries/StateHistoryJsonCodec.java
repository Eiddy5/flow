package org.cses.flow.infrastructure.repositories.executions.postgres.entries;

import org.cses.flow.core.domains.flows.State;
import org.jooq.JSONB;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.ArrayList;
import java.util.List;

final class StateHistoryJsonCodec {

    private StateHistoryJsonCodec() {
    }

    static JSONB encode(State state) {
        JsonObjects values = JsonObjects.Create();
        for (State.History history : state.history()) {
            values.add(JsonObject.Create()
                .put("state", history.state().name())
                .put("date", history.date()));
        }
        return JSONB.valueOf(values.toJson());
    }

    static State decode(String current, JSONB storedHistory) {
        if (current == null || current.isBlank()) {
            throw new IllegalArgumentException(
                "Persisted current state must not be blank"
            );
        }
        if (storedHistory == null) {
            throw new IllegalArgumentException(
                "Persisted state history must not be null"
            );
        }
        try {
            JsonObjects values = JsonObjects.Parse(storedHistory.data());
            List<State.History> history = new ArrayList<>(values.size());
            for (int index = 0; index < values.size(); index++) {
                JsonObject value = values.getObject(index);
                if (!value.has("state") || !value.has("date")) {
                    throw new IllegalArgumentException(
                        "State history entry must contain state and date"
                    );
                }
                history.add(State.History.rehydrate(
                    State.Type.valueOf(value.getString("state")),
                    value.getLong("date")
                ));
            }
            return State.rehydrate(
                State.Type.valueOf(current),
                history
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                "Persisted state history is invalid",
                exception
            );
        }
    }
}
