package org.cses.flow.infrastructure.repositories.executions.codec;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.executions.Generation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationJsonCodecTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    /** 显式传入循环空影响列表，往返当前片段和历史且不虚构退回边界。 */
    @Test
    void roundTripsCurrentAndHistoryWithoutInventingBoundaries() {
        Generation.Current archived = Generation.Current.rehydrate(
                1,
                null,
                null,
                "INITIAL",
                100L, List.of()
        );
        Generation.Current current = Generation.Current.rehydrate(
                2,
                null,
                null,
                "CONDITION_NOT_SATISFIED",
                200L, List.of()
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

    /** 严格往返退回影响范围，并拒绝缺失 affectedTaskRunIds 的旧格式。 */
    @Test
    void requiresExplicitAffectedIdsAndRoundTripsReplayBoundaries() {
        Generation generation = Generation.empty();
        generation.start("source", "target", "correction", List.of("source", "child", "target"));
        Generation restored = GenerationJsonCodec.decode(GenerationJsonCodec.encode(generation));
        assertEquals(generation.current(), restored.current());
        for (String coordinates : List.of("", "\"sourceTaskRunId\":\"source\",\"targetTaskRunId\":\"target\",")) {
            String legacy = "{\"current\":{" + coordinates
                + "\"version\":1,\"reason\":\"legacy\",\"date\":100},\"history\":[]}";
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> GenerationJsonCodec.decode(org.jooq.JSONB.valueOf(legacy)));
        }
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
