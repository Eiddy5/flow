package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-03 用户运行自动流程.md
 */
class Uc03AutomaticTaskFlowTest {

    private static final Map<String, Object> AUTO_TASK =
        Map.of("flow.uc03.auto-task", true);

    @Test
    void s1TargetAutomaticTaskConsumesInputAndPassesOutputOnce() {
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithProperties(AUTO_TASK)) {
            Flow flow = fixture.deploy("""
                key: uc03-s1-flow
                description: automatic input and output
                tasks:
                  - key: prepare-input
                    type: AUTO
                    outputs:
                      - key: payload
                        type: STRING
                    tasks:
                      - key: target-success
                        type: AUTO
                        outputs:
                          - key: result
                            type: STRING
                        tasks:
                          - key: observe-output
                            type: AUTO
                            outputs:
                              - key: observed
                                type: STRING
                """);

            Execution created = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            Execution completed = fixture.executionService().execution(
                fixture.session(),
                created.id()
            ).orElseThrow();
            Task prepare = task(flow, "prepare-input");
            Task target = task(flow, "target-success");
            Task observe = task(flow, "observe-output");

            assertEquals(State.Type.COMPLETED, completed.state().current());
            assertEquals(
                List.of(prepare.id(), target.id(), observe.id()),
                completed.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertEquals(
                Map.of("payload", "prepared"),
                run(completed, prepare).outputs()
            );
            assertEquals(
                Map.of(
                    "outputs",
                    Map.of("payload", "prepared")
                ),
                run(completed, target).inputs()
            );
            assertEquals(
                Map.of("result", "processed-prepared"),
                run(completed, target).outputs()
            );
            assertEquals(
                Map.of(
                    "outputs",
                    Map.of("result", "processed-prepared")
                ),
                run(completed, observe).inputs()
            );
            assertEquals(
                Map.of("observed", "processed-prepared"),
                run(completed, observe).outputs()
            );
            assertEquals(1, completed.taskRunsForTask(target.id()).size());
            assertEquals(1, completed.taskRunsForTask(observe.id()).size());
            assertTrue(completed.activeTaskRuns().isEmpty());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s2ExplicitAutomaticTaskFailureStopsFollowingTask() {
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithProperties(AUTO_TASK)) {
            Flow flow = fixture.deploy("""
                key: uc03-s2-flow
                description: explicit automatic failure
                tasks:
                  - key: prepare-input
                    type: AUTO
                    outputs:
                      - key: payload
                        type: STRING
                    tasks:
                      - key: target-fail
                        type: AUTO
                        tasks:
                          - key: never-run
                            type: AUTO
                """);

            Execution created = fixture.executionService().create(
                fixture.session(),
                flow.id()
            );
            Execution failed = fixture.executionService().execution(
                fixture.session(),
                created.id()
            ).orElseThrow();
            Task prepare = task(flow, "prepare-input");
            Task target = task(flow, "target-fail");
            Task following = task(flow, "never-run");

            assertEquals(State.Type.TERMINATED, failed.state().current());
            assertEquals(State.Type.COMPLETED, run(failed, prepare).state().current());
            assertEquals(State.Type.TERMINATED, run(failed, target).state().current());
            assertEquals(
                "uc03-explicit-failure",
                run(failed, target).error().orElseThrow()
            );
            assertEquals(1, failed.taskRunsForTask(target.id()).size());
            assertTrue(failed.taskRunsForTask(following.id()).isEmpty());
            assertTrue(failed.activeTaskRuns().isEmpty());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    @Test
    void s3UnexpectedAutomaticTaskExceptionRollsBackTheStart() {
        try (WorkflowUcFixture fixture =
                 WorkflowUcFixture.openWithProperties(AUTO_TASK)) {
            Flow flow = fixture.deploy("""
                key: uc03-s3-flow
                description: unexpected automatic exception
                tasks:
                  - key: prepare-input
                    type: AUTO
                    outputs:
                      - key: payload
                        type: STRING
                    tasks:
                      - key: target-throw
                        type: AUTO
                """);
            long before = fixture.executionService().executions(
                fixture.session()
            ).size();

            IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> fixture.executionService().create(
                    fixture.session(),
                    flow.id()
                )
            );
            assertEquals("uc03-unhandled-failure", failure.getMessage());
            assertEquals(
                before,
                fixture.executionService().executions(
                    fixture.session()
                ).size()
            );
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    private static Task task(Flow flow, String key) {
        return flatten(flow.tasks()).stream()
            .filter(task -> task.key().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private static List<Task> flatten(List<Task> tasks) {
        return tasks.stream()
            .flatMap(task -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(task),
                flatten(task.tasks()).stream()
            ))
            .toList();
    }

    private static TaskRun run(Execution execution, Task task) {
        return execution.taskRuns().stream()
            .filter(taskRun -> taskRun.taskId().equals(task.id()))
            .findFirst()
            .orElseThrow();
    }
}
