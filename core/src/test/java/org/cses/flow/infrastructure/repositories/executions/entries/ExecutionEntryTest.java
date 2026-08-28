package org.cses.flow.infrastructure.repositories.executions.entries;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionEntryTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void mapsTheCompleteStateValueThroughTheEntryBoundary() {
        State state = State.rehydrate(
            State.Type.RUNNING,
            List.of(
                State.History.rehydrate(State.Type.CREATED, 100L),
                State.History.rehydrate(State.Type.RUNNING, 200L)
            )
        );
        Execution execution = Execution.rehydrate(
            "execution-1",
            "company-1",
            ActorRef.create("actor-1", "Flow User"),
            100L,
            "flow-1",
            1,
            Map.of("amount", 1200),
            state,
            List.of()
        );

        ExecutionEntry entry = ExecutionEntry.from(execution);
        JsonObject storedState = JsonObject.Parse(entry.state.data());

        assertEquals(
            Set.of("current", "history"),
            storedState.asMap().keySet()
        );
        Execution restored = entry.to(List.of());
        assertEquals(state, restored.state());
        assertEquals(execution.inputs(), restored.inputs());
    }
}
