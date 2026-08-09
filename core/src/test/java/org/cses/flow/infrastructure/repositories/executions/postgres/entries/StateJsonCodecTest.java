package org.cses.flow.infrastructure.repositories.executions.postgres.entries;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.flows.State;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StateJsonCodecTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void roundTripsOneCompleteStateObject() {
        State state = State.created().running().paused();

        JSONB encoded = StateJsonCodec.encode(state);
        JsonObject stored = JsonObject.Parse(encoded.data());

        assertEquals(Set.of("current", "history"), stored.asMap().keySet());
        assertEquals(state.current().name(), stored.getString("current"));
        assertEquals(
            state.history().size(),
            stored.getObjects("history").size()
        );
        assertEquals(state, StateJsonCodec.decode(encoded));
    }

    @Test
    void rejectsAStateWhoseCurrentDoesNotMatchItsHistory() {
        JSONB stored = JSONB.valueOf("""
            {
              "current": "RUNNING",
              "history": [
                {"state": "CREATED", "date": 1}
              ]
            }
            """);

        assertThrows(
            IllegalArgumentException.class,
            () -> StateJsonCodec.decode(stored)
        );
    }

    @Test
    void rejectsAnIncompleteStateObject() {
        assertThrows(
            IllegalArgumentException.class,
            () -> StateJsonCodec.decode(JSONB.valueOf("""
                {"current": "CREATED"}
                """))
        );
    }
}
