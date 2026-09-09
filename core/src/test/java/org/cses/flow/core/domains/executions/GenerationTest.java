package org.cses.flow.core.domains.executions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTest {

    @Test
    void normalExecutionStartsWithoutAFakeCurrentVersion() {
        Generation generation = Generation.empty();

        assertFalse(generation.active());
        assertTrue(generation.current().isEmpty());
        assertTrue(generation.history().currents().isEmpty());
    }

    /** 显式提供受影响记录，连续推进并确认原当前片段完整进入历史。 */
    @Test
    void rewindVersionsMoveTheSameCurrentObjectsIntoHistory() {
        Generation generation = Generation.empty();

        generation.start("pause-1", "prepare-1", "资料需要修改", List.of("pause-1", "prepare-1"));
        Generation.Current first = generation.current().orElseThrow();

        generation.advance("pause-2", "prepare-2", "仍需补充资料", List.of("pause-2", "prepare-2"));
        Generation.Current second = generation.current().orElseThrow();

        assertEquals(1, first.version());
        assertEquals("pause-1", first.sourceTaskRunId().orElseThrow());
        assertEquals("prepare-1", first.targetTaskRunId().orElseThrow());
        assertEquals(List.of(first), generation.history().currents());
        assertEquals(2, second.version());

        generation.complete();

        assertTrue(generation.current().isEmpty());
        assertEquals(List.of(first, second), generation.history().currents());
    }

    @Test
    void loopRoundUsesVersionAndReasonWithoutRewindBoundaries() {
        Generation generation = Generation.empty();

        generation.start("INITIAL");
        generation.advance("FIXED_COUNT_NOT_REACHED");

        Generation.Current current = generation.current().orElseThrow();
        assertEquals(2, current.version());
        assertTrue(current.sourceTaskRunId().isEmpty());
        assertTrue(current.targetTaskRunId().isEmpty());
        assertEquals("FIXED_COUNT_NOT_REACHED", current.reason());
    }

    /** 重建带空影响列表的循环片段，拒绝不连续的版本历史。 */
    @Test
    void rehydratedVersionsMustBeConsecutive() {
        Generation.Current second = Generation.Current.rehydrate(
                2,
                null,
                null,
                "INITIAL",
                100L, List.of()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> Generation.rehydrate(
                        second,
                        Generation.History.rehydrate(List.of())
                )
        );
    }
}
