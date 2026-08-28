package org.cses.flow.infrastructure.repositories.flows.entries;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.session.RecordState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowEntryTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void roundTripsVariablesThroughTheFlowFieldCodec() {
        ActorRef actor = ActorRef.create("actor-1", "Flow User");
        Flow flow = Flow.rehydrate(
            "flow-id",
            "company-1",
            "flow-key",
            true,
            null,
            "description",
            Map.of(
                "environment",
                "production",
                "options",
                Map.of("retries", 3)
            ),
            List.of(),
            List.of(),
            List.of(),
            RecordState.Open,
            actor,
            actor,
            null,
            100L,
            200L,
            null,
            "key: flow-key"
        );

        Flow restored = FlowEntry.from(flow).to();

        assertEquals(flow.id(), restored.id());
        assertEquals(flow.variables(), restored.variables());
        assertEquals(flow.creator(), restored.creator());
        assertEquals(flow.createdAt(), restored.createdAt());
    }
}
