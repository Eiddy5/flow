package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-02 用户启动、查询与取消 Flow.md
 */
class Uc02ExecutionLifecycleTest {

    @Test
    void s8TwoExecutionsOfOneFlowKeepIndependentCompleteHistories() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy("""
                key: uc02-s8-flow
                description: two independent executions
                tasks:
                  - key: first
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                  - key: second
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                  - key: third
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                """);

            Execution firstStarted = fixture.startAndAwait(flow);
            Execution secondStarted = fixture.startAndAwait(flow);
            Execution first = fixture.executionService().execution(
                fixture.session(),
                firstStarted.id()
            ).orElseThrow();
            Execution second = fixture.executionService().execution(
                fixture.session(),
                secondStarted.id()
            ).orElseThrow();

            assertNotEquals(first.id(), second.id());
            assertEquals(State.Type.SUCCESS, first.state().current());
            assertEquals(State.Type.SUCCESS, second.state().current());
            assertEquals(flow.key(), first.flowKey());
            assertEquals(flow.key(), second.flowKey());
            assertEquals(1L, first.flowVersion());
            assertEquals(1L, second.flowVersion());
            assertEquals(
                flow.tasks().stream().map(task -> task.id()).toList(),
                first.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(
                flow.tasks().stream().map(task -> task.id()).toList(),
                second.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertTrue(first.taskRuns().stream().allMatch(taskRun ->
                taskRun.state().current() == State.Type.SUCCESS
                    && taskRun.inputs().isEmpty()
                    && taskRun.outputs().isEmpty()
            ));
            assertTrue(second.taskRuns().stream().allMatch(taskRun ->
                taskRun.state().current() == State.Type.SUCCESS
                    && taskRun.inputs().isEmpty()
                    && taskRun.outputs().isEmpty()
            ));
            assertTrue(first.taskRuns().stream().map(TaskRun::id)
                .noneMatch(second.taskRuns().stream()
                    .map(TaskRun::id)
                    .collect(java.util.stream.Collectors.toSet())::contains));
            assertTrue(first.activeTaskRuns().isEmpty());
            assertTrue(second.activeTaskRuns().isEmpty());
        }
    }

    @Test
    void s9FreshServerStartsThePersistedPublishedFlowInOrder() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy("""
                key: uc02-s9-flow
                description: persisted definition after restart
                tasks:
                  - key: first
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                  - key: second
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                  - key: third
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                """);
            String flowKey = flow.key();
            java.util.List<String> taskIds = flow.tasks().stream()
                .map(task -> task.id())
                .toList();

            fixture.restartServer();
            Flow persisted = fixture.flowService().latestFlow(
                fixture.session(),
                flowKey
            ).orElseThrow();
            Execution completed = fixture.startAndAwait(persisted);
            Execution reloaded = fixture.executionService().execution(
                fixture.session(),
                completed.id()
            ).orElseThrow();

            assertEquals(flowKey, persisted.key());
            assertEquals(flow.id(), persisted.id());
            assertEquals(1L, persisted.reversion());
            assertEquals(State.Type.SUCCESS, reloaded.state().current());
            assertEquals(
                taskIds,
                reloaded.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(3, reloaded.taskRuns().size());
            assertEquals(
                3L,
                reloaded.taskRuns().stream()
                    .map(TaskRun::id)
                    .distinct()
                    .count()
            );
            assertTrue(reloaded.taskRuns().stream().allMatch(taskRun ->
                taskRun.state().current() == State.Type.SUCCESS
            ));
            assertTrue(reloaded.activeTaskRuns().isEmpty());
        }
    }
}
