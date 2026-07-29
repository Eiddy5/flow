package org.cses.flow.core.domains.executions;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class ExecutionTest {

    @Test
    void createTaskRunShouldGenerateStableChildIdentity() {
        Execution execution = Execution.create(
            "execution-domain-company",
            "execution-domain-flow",
            1
        );

        TaskRun first = execution.createTaskRun(
            "execution-domain-task-1",
            null,
            Map.of("request", "A-1")
        );
        TaskRun second = execution.createTaskRun(
            "execution-domain-task-2",
            first.id(),
            Map.of("request", "A-2")
        );

        assertFalse(first.id().isBlank());
        assertFalse(second.id().isBlank());
        assertNotEquals(first.id(), second.id());
        assertEquals(
            first.id(),
            execution.taskRuns().getFirst().id()
        );
        assertEquals(
            second.id(),
            execution.taskRuns().getLast().id()
        );
    }
}
