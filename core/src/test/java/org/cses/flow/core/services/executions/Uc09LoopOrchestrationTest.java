package org.cses.flow.core.services.executions;

import io.micronaut.context.annotation.Requires;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.extensions.flow.Loop;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-09 用户定义并运行循环流程.md
 */
class Uc09LoopOrchestrationTest {

    private static final Map<String, Object> LOOP_PROBE = Map.of(
        "flow.uc09.loop-probe", true
    );

    @Test
    void s1FixedLoopRunsEveryBodyStepThreeTimesBeforeFollowingStep() {
        try (WorkflowUcFixture fixture = fixture()) {
            Flow flow = fixture.deploy(fixedLoopYaml("uc09-s1-flow", 3));

            Execution completed = startAndQuery(fixture, flow);
            Task loop = task(flow, "repeat-three-times");
            Task first = task(flow, "body-first");
            Task second = task(flow, "body-second");
            Task following = task(flow, "after-loop");
            TaskRun loopRun = onlyRun(completed, loop);

            // S1 预期：两个步骤每轮按声明顺序完整执行，然后才开始下一轮。
            assertEquals(
                List.of(
                    loop.id(),
                    first.id(), second.id(),
                    first.id(), second.id(),
                    first.id(), second.id(),
                    following.id()
                ),
                completed.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertSuccessfulIterations(completed, first, loopRun, 1, 2, 3);
            assertSuccessfulIterations(completed, second, loopRun, 1, 2, 3);
            assertIterationOutputs(completed, first, 1, 2, 3);
            assertIterationOutputs(completed, second, 1, 2, 3);

            // S1 预期：循环后续步骤只执行一次。
            assertEquals(State.Type.SUCCESS, loopRun.state().current());
            assertEquals(State.Type.SUCCESS, onlyRun(completed, following)
                .state().current());

            // S1 场景结束：流程已完成，没有待处理步骤。
            assertCompletedWithoutPendingWork(fixture, completed);
        }
    }

    @Test
    void s2LoopUntilStopsAfterSecondCompletedIteration() {
        try (WorkflowUcFixture fixture = fixture()) {
            Flow flow = fixture.deploy(loopUntilYaml(
                "uc09-s2-flow",
                "condition-second",
                3
            ));

            Execution completed = startAndQuery(fixture, flow);
            Task loop = task(flow, "repeat-until-done");
            Task body = task(flow, "body-step");
            Task condition = task(flow, "condition-second");
            Task following = task(flow, "after-loop");
            TaskRun loopRun = onlyRun(completed, loop);

            // S2 预期：每轮完整结束后才判断，第一轮继续、第二轮停止。
            assertEquals(
                List.of(
                    loop.id(),
                    body.id(), condition.id(),
                    body.id(), condition.id(),
                    following.id()
                ),
                completed.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertSuccessfulIterations(completed, body, loopRun, 1, 2);
            assertSuccessfulIterations(completed, condition, loopRun, 1, 2);
            assertIterationOutputs(completed, body, 1, 2);
            assertEquals(
                List.of("WAIT", "DONE"),
                runs(completed, condition).stream()
                    .map(run -> run.outputs().get("status"))
                    .toList()
            );

            // S2 预期：不会创建第三轮，后续步骤只执行一次。
            assertEquals(2, runs(completed, condition).size());
            assertEquals(State.Type.SUCCESS, loopRun.state().current());
            assertEquals(State.Type.SUCCESS, onlyRun(completed, following)
                .state().current());

            // S2 场景结束：流程已完成，没有待处理步骤。
            assertCompletedWithoutPendingWork(fixture, completed);
        }
    }

    @Test
    void s3LoopUntilCompletesOneFullIterationWhenConditionMatchesFirst() {
        try (WorkflowUcFixture fixture = fixture()) {
            Flow flow = fixture.deploy(loopUntilYaml(
                "uc09-s3-flow",
                "condition-first",
                3
            ));

            Execution completed = startAndQuery(fixture, flow);
            Task loop = task(flow, "repeat-until-done");
            Task body = task(flow, "body-step");
            Task condition = task(flow, "condition-first");
            Task following = task(flow, "after-loop");
            TaskRun loopRun = onlyRun(completed, loop);

            // S3 预期：先完整执行第一轮，再判断条件。
            assertEquals(
                List.of(loop.id(), body.id(), condition.id(), following.id()),
                completed.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertSuccessfulIterations(completed, body, loopRun, 1);
            assertSuccessfulIterations(completed, condition, loopRun, 1);
            assertIterationOutputs(completed, body, 1);
            assertEquals(
                Map.of("status", "DONE"),
                onlyRun(completed, condition).outputs()
            );

            // S3 预期：不会创建第二轮，后续步骤只执行一次。
            assertEquals(1, runs(completed, body).size());
            assertEquals(1, runs(completed, condition).size());
            assertEquals(State.Type.SUCCESS, loopRun.state().current());
            assertEquals(State.Type.SUCCESS, onlyRun(completed, following)
                .state().current());

            // S3 场景结束：流程已完成，没有待处理步骤。
            assertCompletedWithoutPendingWork(fixture, completed);
        }
    }

    @Test
    void s4LoopUntilFailsAfterMaximumCompletedIterations() {
        try (WorkflowUcFixture fixture = fixture()) {
            Flow flow = fixture.deploy(loopUntilYaml(
                "uc09-s4-flow",
                "condition-never",
                2
            ));

            Execution failed = startAndQuery(fixture, flow);
            Task loop = task(flow, "repeat-until-done");
            Task body = task(flow, "body-step");
            Task condition = task(flow, "condition-never");
            Task following = task(flow, "after-loop");
            TaskRun loopRun = onlyRun(failed, loop);

            // S4 预期：循环体完整执行两轮，且不创建第三轮。
            assertEquals(
                List.of(
                    loop.id(),
                    body.id(), condition.id(),
                    body.id(), condition.id()
                ),
                failed.taskRuns().stream().map(TaskRun::taskId).toList()
            );
            assertSuccessfulIterations(failed, body, loopRun, 1, 2);
            assertSuccessfulIterations(failed, condition, loopRun, 1, 2);
            assertIterationOutputs(failed, body, 1, 2);
            assertEquals(
                List.of("WAIT", "WAIT"),
                runs(failed, condition).stream()
                    .map(run -> run.outputs().get("status"))
                    .toList()
            );

            // S4 预期：流程明确失败，不执行循环后续步骤。
            assertEquals(State.Type.FAILED, loopRun.state().current());
            assertTrue(loopRun.error().orElseThrow().contains(
                "after 2 iterations"
            ));
            assertNoRun(failed, following);

            // S4 场景结束：流程处于失败终态，没有待处理步骤。
            assertFailedWithoutPendingWork(fixture, failed);
        }
    }

    @Test
    void s5BodyFailureStopsFixedAndConditionalLoops() {
        try (WorkflowUcFixture fixture = fixture()) {
            Flow fixedFlow = fixture.deploy(failingLoopYaml(
                "uc09-s5-fixed-flow",
                false
            ));
            Flow conditionalFlow = fixture.deploy(failingLoopYaml(
                "uc09-s5-conditional-flow",
                true
            ));

            Execution fixedFailed = startAndQuery(fixture, fixedFlow);
            Execution conditionalFailed = startAndQuery(
                fixture,
                conditionalFlow
            );

            // S5 预期：固定次数循环体失败后停止，无后续轮次或后续步骤。
            assertBodyFailureStopsLoop(fixedFailed, fixedFlow);

            // S5 预期：条件循环体失败后停止，无后续轮次或后续步骤。
            assertBodyFailureStopsLoop(conditionalFailed, conditionalFlow);

            // S5 场景结束：两个流程均处于失败终态，没有待处理步骤。
            assertFailedWithoutPendingWork(fixture, fixedFailed);
            assertFailedWithoutPendingWork(fixture, conditionalFailed);
            assertEquals(
                Set.of(fixedFailed.id(), conditionalFailed.id()),
                fixture.executionService().executions(fixture.session())
                    .stream()
                    .map(Execution::id)
                    .collect(java.util.stream.Collectors.toSet())
            );
        }
    }

    @Test
    void s6InvalidLoopDraftsAreRejectedAndDeletedWithoutResources() {
        try (WorkflowUcFixture fixture = fixture()) {
            List<InvalidDefinition> definitions = invalidDefinitions();
            List<FlowDraft> drafts = definitions.stream()
                .map(definition -> fixture.flowService().saveDraft(
                    fixture.session(),
                    definition.yaml()
                ))
                .toList();

            assertEquals(5, fixture.flowService().drafts(
                fixture.session()
            ).size());
            assertTrue(fixture.executionService().executions(
                fixture.session()
            ).isEmpty());

            for (int index = 0; index < definitions.size(); index++) {
                InvalidDefinition definition = definitions.get(index);
                FlowDraft draft = drafts.get(index);

                // S6 预期：每个无效循环定义在发布时都被明确拒绝。
                RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> fixture.flowService().deploy(
                        fixture.session(),
                        draft.id()
                    )
                );
                assertTrue(
                    messages(failure).contains(definition.errorFragment()),
                    () -> "Unexpected rejection message: "
                        + messages(failure)
                );

                // S6 预期：拒绝后不产生正式版本或运行流程。
                assertTrue(fixture.flowService().latestFlow(
                    fixture.session(),
                    draft.id()
                ).isEmpty());
                assertTrue(fixture.executionService().executions(
                    fixture.session()
                ).isEmpty());
            }

            for (FlowDraft draft : drafts) {
                FlowDraft deleted = fixture.flowService().deleteDraft(
                    fixture.session(),
                    draft.id()
                );
                assertTrue(deleted.isDeleted());
                assertTrue(fixture.flowService().draft(
                    fixture.session(),
                    draft.id()
                ).isEmpty());
            }

            // S6 场景结束：草稿已全部删除，没有遗留正式或运行资源。
            assertTrue(fixture.flowService().drafts(fixture.session())
                .isEmpty());
            for (FlowDraft draft : drafts) {
                assertTrue(fixture.flowService().latestFlow(
                    fixture.session(),
                    draft.id()
                ).isEmpty());
            }
            assertTrue(fixture.executionService().executions(
                fixture.session()
            ).isEmpty());
            assertTrue(fixture.externalTaskService().waitingTasks(
                fixture.session()
            ).isEmpty());
        }
    }

    private static WorkflowUcFixture fixture() {
        return WorkflowUcFixture.openWithProperties(LOOP_PROBE);
    }

    private static Execution startAndQuery(
        WorkflowUcFixture fixture,
        Flow flow
    ) {
        Execution started = fixture.executionService().create(
            fixture.session(),
            flow.id()
        );
        return fixture.executionService().execution(
            fixture.session(),
            started.id()
        ).orElseThrow();
    }

    private static void assertSuccessfulIterations(
        Execution execution,
        Task task,
        TaskRun loopRun,
        int... expectedIterations
    ) {
        List<TaskRun> runs = runs(execution, task);
        assertEquals(
            integers(expectedIterations),
            runs.stream()
                .map(run -> run.iteration().orElseThrow())
                .toList()
        );
        assertTrue(runs.stream().allMatch(run ->
            run.parentId().orElseThrow().equals(loopRun.id())
        ));
        assertTrue(runs.stream().allMatch(run ->
            run.state().is(State.Type.SUCCESS)
        ));
    }

    private static void assertIterationOutputs(
        Execution execution,
        Task task,
        int... expectedIterations
    ) {
        assertEquals(
            integers(expectedIterations),
            runs(execution, task).stream()
                .map(run -> run.outputs().get("iteration"))
                .toList()
        );
    }

    private static List<Integer> integers(int... values) {
        List<Integer> result = new ArrayList<>(values.length);
        for (int value : values) {
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static void assertBodyFailureStopsLoop(
        Execution execution,
        Flow flow
    ) {
        Task loop = task(flow, "repeat-until-failure");
        Task body = task(flow, "body-step");
        Task condition = task(flow, "condition-never");
        Task failure = task(flow, "body-fail");
        Task following = task(flow, "after-loop");
        TaskRun loopRun = onlyRun(execution, loop);

        assertEquals(
            List.of(loop.id(), body.id(), condition.id(), failure.id()),
            execution.taskRuns().stream().map(TaskRun::taskId).toList()
        );
        assertSuccessfulIterations(execution, body, loopRun, 1);
        assertSuccessfulIterations(execution, condition, loopRun, 1);
        assertIterationOutputs(execution, body, 1);
        assertEquals(
            Map.of("status", "WAIT"),
            onlyRun(execution, condition).outputs()
        );
        TaskRun failedRun = onlyRun(execution, failure);
        assertEquals(1, failedRun.iteration().orElseThrow());
        assertEquals(loopRun.id(), failedRun.parentId().orElseThrow());
        assertEquals(State.Type.FAILED, failedRun.state().current());
        assertEquals("uc09-body-failure", failedRun.error().orElseThrow());
        assertEquals(State.Type.KILLED, loopRun.state().current());
        assertNoRun(execution, following);
    }

    private static void assertCompletedWithoutPendingWork(
        WorkflowUcFixture fixture,
        Execution execution
    ) {
        assertEquals(State.Type.SUCCESS, execution.state().current());
        assertTrue(execution.unfinishedTaskRuns().isEmpty());
        assertTrue(fixture.externalTaskService().waitingTasks(
            fixture.session()
        ).isEmpty());
    }

    private static void assertFailedWithoutPendingWork(
        WorkflowUcFixture fixture,
        Execution execution
    ) {
        assertEquals(State.Type.FAILED, execution.state().current());
        assertTrue(execution.unfinishedTaskRuns().isEmpty());
        assertTrue(fixture.externalTaskService().waitingTasks(
            fixture.session()
        ).isEmpty());
    }

    private static Task task(Flow flow, String key) {
        return flow.allTasks().stream()
            .filter(candidate -> candidate.key().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private static List<TaskRun> runs(Execution execution, Task task) {
        return execution.taskRunsForTask(task.id());
    }

    private static TaskRun onlyRun(Execution execution, Task task) {
        List<TaskRun> runs = runs(execution, task);
        assertEquals(1, runs.size());
        return runs.getFirst();
    }

    private static void assertNoRun(Execution execution, Task task) {
        assertTrue(runs(execution, task).isEmpty());
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor.getMessage() != null) {
                if (!result.isEmpty()) {
                    result.append(" | ");
                }
                result.append(cursor.getMessage());
            }
            cursor = cursor.getCause();
        }
        return result.toString();
    }

    private static String fixedLoopYaml(String key, int times) {
        return """
            key: %s
            description: fixed loop with ordered body
            tasks:
              - key: repeat-three-times
                type: %s
                times: %d
                tasks:
                  - key: body-first
                    type: %s
                    outputs:
                      - key: iteration
                        type: INTEGER
                  - key: body-second
                    type: %s
                    outputs:
                      - key: iteration
                        type: INTEGER
              - key: after-loop
                type: %s
            """.formatted(
                key,
                Loop.class.getCanonicalName(),
                times,
                LoopProbeTask.class.getCanonicalName(),
                LoopProbeTask.class.getCanonicalName(),
                LoopProbeTask.class.getCanonicalName()
            );
    }

    private static String loopUntilYaml(
        String key,
        String conditionTaskKey,
        int maxIterations
    ) {
        return """
            key: %s
            description: post-condition loop
            tasks:
              - key: repeat-until-done
                type: %s
                condition: 'outputs.%s.status == "DONE"'
                maxIterations: %d
                tasks:
                  - key: body-step
                    type: %s
                    outputs:
                      - key: iteration
                        type: INTEGER
                  - key: %s
                    type: %s
                    outputs:
                      - key: status
                        type: STRING
              - key: after-loop
                type: %s
            """.formatted(
                key,
                LoopUntil.class.getCanonicalName(),
                conditionTaskKey,
                maxIterations,
                LoopProbeTask.class.getCanonicalName(),
                conditionTaskKey,
                LoopProbeTask.class.getCanonicalName(),
                LoopProbeTask.class.getCanonicalName()
            );
    }

    private static String failingLoopYaml(
        String key,
        boolean conditional
    ) {
        if (conditional) {
            return """
                key: %s
                description: conditional loop body failure
                tasks:
                  - key: repeat-until-failure
                    type: %s
                    condition: 'outputs.condition-never.status == "DONE"'
                    maxIterations: 3
                    tasks:
                      - key: body-step
                        type: %s
                        outputs:
                          - key: iteration
                            type: INTEGER
                      - key: condition-never
                        type: %s
                        outputs:
                          - key: status
                            type: STRING
                      - key: body-fail
                        type: %s
                  - key: after-loop
                    type: %s
                """.formatted(
                    key,
                    LoopUntil.class.getCanonicalName(),
                    LoopProbeTask.class.getCanonicalName(),
                    LoopProbeTask.class.getCanonicalName(),
                    LoopProbeTask.class.getCanonicalName(),
                    LoopProbeTask.class.getCanonicalName()
                );
        }
        return """
            key: %s
            description: fixed loop body failure
            tasks:
              - key: repeat-until-failure
                type: %s
                times: 3
                tasks:
                  - key: body-step
                    type: %s
                    outputs:
                      - key: iteration
                        type: INTEGER
                  - key: condition-never
                    type: %s
                    outputs:
                      - key: status
                        type: STRING
                  - key: body-fail
                    type: %s
              - key: after-loop
                type: %s
            """.formatted(
                key,
                Loop.class.getCanonicalName(),
                LoopProbeTask.class.getCanonicalName(),
                LoopProbeTask.class.getCanonicalName(),
                LoopProbeTask.class.getCanonicalName(),
                LoopProbeTask.class.getCanonicalName()
            );
    }

    private static List<InvalidDefinition> invalidDefinitions() {
        return List.of(
            new InvalidDefinition(
                """
                    key: uc09-s6-empty-body
                    tasks:
                      - key: invalid-loop
                        type: %s
                        times: 1
                    """.formatted(Loop.class.getCanonicalName()),
                "requires at least one child Task"
            ),
            new InvalidDefinition(
                """
                    key: uc09-s6-zero-times
                    tasks:
                      - key: invalid-loop
                        type: %s
                        times: 0
                        tasks:
                          - key: work
                            type: %s
                    """.formatted(
                        Loop.class.getCanonicalName(),
                        AutomaticTask.class.getCanonicalName()
                ),
                "times"
            ),
            new InvalidDefinition(
                """
                    key: uc09-s6-zero-max-iterations
                    tasks:
                      - key: invalid-loop-until
                        type: %s
                        condition: 'outputs.check.status == "DONE"'
                        maxIterations: 0
                        tasks:
                          - key: check
                            type: %s
                            outputs:
                              - key: status
                                type: STRING
                    """.formatted(
                        LoopUntil.class.getCanonicalName(),
                        AutomaticTask.class.getCanonicalName()
                    ),
                "maxIterations"
            ),
            new InvalidDefinition(
                """
                    key: uc09-s6-invalid-condition
                    tasks:
                      - key: invalid-loop-until
                        type: %s
                        condition: 'outputs.check.status =='
                        maxIterations: 3
                        tasks:
                          - key: check
                            type: %s
                            outputs:
                              - key: status
                                type: STRING
                    """.formatted(
                        LoopUntil.class.getCanonicalName(),
                        AutomaticTask.class.getCanonicalName()
                    ),
                "Unsupported Express condition"
            ),
            new InvalidDefinition(
                """
                    key: uc09-s6-wrong-output-reference
                    tasks:
                      - key: invalid-loop-until
                        type: %s
                        condition: 'outputs.missing.status == "DONE"'
                        maxIterations: 3
                        tasks:
                          - key: check
                            type: %s
                            outputs:
                              - key: status
                                type: STRING
                    """.formatted(
                        LoopUntil.class.getCanonicalName(),
                        AutomaticTask.class.getCanonicalName()
                    ),
                "outside its body"
            )
        );
    }

    private record InvalidDefinition(String yaml, String errorFragment) {
    }

    /** Deterministic result probe used only by UC-09 orchestration tests. */
    @Plugin
    @Requires(property = "flow.uc09.loop-probe", value = "true")
    @SuperBuilder
    @NoArgsConstructor
    public static final class LoopProbeTask
        extends Task implements RunnableTask {

        @Override
        public RunResult run(RunContext context) {
            return switch (key()) {
                case "body-first", "body-second", "body-step" ->
                    RunResult.success(Map.of(
                        "iteration",
                        iteration(context)
                    ));
                case "condition-first" ->
                    RunResult.success(Map.of("status", "DONE"));
                case "condition-second" -> RunResult.success(Map.of(
                    "status",
                    iteration(context) >= 2 ? "DONE" : "WAIT"
                ));
                case "condition-never" ->
                    RunResult.success(Map.of("status", "WAIT"));
                case "body-fail" -> RunResult.failed(
                    "uc09-body-failure"
                );
                default -> RunResult.success(Map.of());
            };
        }

        private static int iteration(RunContext context) {
            Object loop = context.inputs().get("loop");
            if (!(loop instanceof Map<?, ?> values)
                || !(values.get("iteration") instanceof Number value)) {
                throw new IllegalStateException(
                    "UC-09 loop probe requires loop.iteration"
                );
            }
            return value.intValue();
        }
    }
}
