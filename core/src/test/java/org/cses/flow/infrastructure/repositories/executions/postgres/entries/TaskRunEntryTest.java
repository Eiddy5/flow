package org.cses.flow.infrastructure.repositories.executions.postgres.entries;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.executions.TaskRun;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TaskRunEntryTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void roundTripsTheLoopIterationFact() {
        TaskRun taskRun = TaskRun.create(
            "task-id",
            "loop-run-id",
            Map.of("loop", Map.of("iteration", 2)),
            2
        );
        OffsetDateTime now = OffsetDateTime.parse(
            "2026-08-05T12:00:00+08:00"
        );

        TaskRunEntry entry = TaskRunEntry.fromDomain(
            "execution-id",
            taskRun,
            1,
            now,
            now
        );
        TaskRun restored = entry.toDomain();

        assertEquals(2, entry.getIteration());
        assertEquals(2, restored.iteration().orElseThrow());
        assertEquals(taskRun.parentTaskRunId(), restored.parentTaskRunId());
        assertEquals(taskRun.inputs(), restored.inputs());
        assertEquals(taskRun.state(), restored.state());
    }
}
