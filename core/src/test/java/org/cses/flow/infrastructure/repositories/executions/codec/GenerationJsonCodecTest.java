package org.cses.flow.infrastructure.repositories.executions.codec;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.executions.Generation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GenerationJsonCodecTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void roundTripsCurrentAndHistoryWithoutInventingBoundaries() {
        Generation.Current archived = Generation.Current.rehydrate(
                1,
                null,
                null,
                "INITIAL",
                100L
        );
        Generation.Current current = Generation.Current.rehydrate(
                2,
                null,
                null,
                "CONDITION_NOT_SATISFIED",
                200L
        );
        Generation generation = Generation.rehydrate(
                current,
                Generation.History.rehydrate(List.of(archived))
        );

        Generation restored = GenerationJsonCodec.decode(
                GenerationJsonCodec.encode(generation)
        );

        assertEquals(current, restored.current().orElseThrow());
        assertEquals(List.of(archived), restored.history().currents());
        assertTrue(restored.current().orElseThrow()
                .sourceTaskRunId().isEmpty());
    }

    @Test
    void roundTripsAnEmptyGeneration() {
        Generation restored = GenerationJsonCodec.decode(
                GenerationJsonCodec.encode(Generation.empty())
        );

        assertTrue(restored.current().isEmpty());
        assertTrue(restored.history().currents().isEmpty());
    }
}
