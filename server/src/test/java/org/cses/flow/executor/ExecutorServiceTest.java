package org.cses.flow.executor;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutorServiceTest {

    private static final Context PLUGINS = builtInContext();
    private final ExecutorService executorService = new ExecutorService();

    @Test
    void contextContainsOnlyExecutorRuntimeData() {
        assertEquals(
            Set.of(
                "execution",
                "flow",
                "nexts",
                "workerTasks",
                "pausedTaskRuns",
                "orchestrationCompletions",
                "states"
            ),
            Arrays.stream(ExecutorContext.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(java.util.stream.Collectors.toSet())
        );
    }

    @Test
    void handleNextOnlyStagesTheNextTaskRunBatch() {
        Flow flow = deploy(Map.of(
            "key", "stage-only",
            "tasks", List.of(Map.of(
                "key", "prepare",
                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        executorService.handleNext(context);

        assertTrue(execution.state().is(State.Type.CREATED));
        assertTrue(execution.taskRuns().isEmpty());
        assertEquals(1, context.nexts().size());
        assertEquals(
            flow.tasks().getFirst().id(),
            context.nexts().getFirst().taskId()
        );
        assertEquals(
            List.of(State.Type.CREATED),
            context.states()
        );
        assertTrue(context.workerTasks().isEmpty());
    }

    @Test
    void onNextsConsumesThePlanAndStartsTheExecution() {
        Flow flow = deploy(Map.of(
            "key", "apply-nexts",
            "tasks", List.of(Map.of(
                "key", "prepare",
                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);
        executorService.handleNext(context);
        String plannedTaskRunId = context.nexts()
            .getFirst()
            .id();

        boolean executionChanged = executorService.onNexts(context);

        assertTrue(executionChanged);
        assertTrue(context.nexts().isEmpty());
        assertTrue(execution.state().is(State.Type.RUNNING));
        assertEquals(1, execution.taskRuns().size());
        assertEquals(
            plannedTaskRunId,
            execution.taskRuns().getFirst().id()
        );
        assertEquals(1, context.workerTasks().size());
        assertEquals(
            List.of(State.Type.CREATED, State.Type.RUNNING),
            context.states()
        );
        assertEquals(1, execution.taskRuns().size());
    }

    @Test
    void invalidFirstBatchDoesNotPartiallyStartTheExecution() {
        Flow flow = deploy(Map.of(
            "key", "invalid-first-batch",
            "tasks", List.of(Map.of(
                "key", "prepare",
                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);
        String taskId = flow.tasks().getFirst().id();
        context.stageNexts(
            List.of(
                TaskRun.create(taskId, null, Map.of()),
                TaskRun.create(taskId, null, Map.of())
            )
        );

        assertThrows(
            WorkflowException.class,
            () -> executorService.onNexts(context)
        );

        assertTrue(execution.state().is(State.Type.CREATED));
        assertTrue(execution.taskRuns().isEmpty());
        assertTrue(context.workerTasks().isEmpty());
        assertEquals(
            List.of(State.Type.CREATED),
            context.states()
        );
    }

    @Test
    void taskWithoutRuntimeCapabilityIsRejectedBeforeAttachment() {
        Flow flow = flowWith(NoCapabilityTask.builder()
            .id("invalid-task")
            .key("invalid-task")
            .build());
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);
        executorService.handleNext(context);

        assertThrows(
            IllegalStateException.class,
            () -> executorService.onNexts(context)
        );

        assertTrue(execution.state().is(State.Type.CREATED));
        assertTrue(execution.taskRuns().isEmpty());
    }

    @Test
    void taskWithBothRuntimeCapabilitiesIsRejectedBeforeAttachment() {
        Flow flow = flowWith(ConflictingCapabilityTask.builder()
            .id("invalid-task")
            .key("invalid-task")
            .build());
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);
        executorService.handleNext(context);

        assertThrows(
            IllegalStateException.class,
            () -> executorService.onNexts(context)
        );

        assertTrue(execution.state().is(State.Type.CREATED));
        assertTrue(execution.taskRuns().isEmpty());
    }

    @Test
    void handleNextStagesOnlyFirstRunnableChildForOrdinaryParent() {
        Flow flow = deploy(Map.of(
            "key", "serial-nexts",
            "tasks", List.of(Map.of(
                "key", "parent",
                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName(),
                "tasks", List.of(
                    Map.of(
                        "key", "first",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                    ),
                    Map.of(
                        "key", "second",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                    )
                )
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        executorService.handleNext(context);
        executorService.onNexts(context);
        WorkerTask parent = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, parent);
        executorService.applyResult(
            context,
            WorkerTaskResult.completed(parent, Map.of())
        );

        executorService.handleNext(context);

        assertEquals(
            List.of("first"),
            context.nexts().stream()
                .map(TaskRun::taskId)
                .map(taskId -> flow.findTask(taskId).orElseThrow())
                .map(task -> task.key())
                .toList()
        );
    }

    @Test
    void handleKeepsParallelScopeRunningAndStagesChildrenAsOneBatch() {
        Flow flow = deploy(Map.of(
            "key", "parallel-nexts",
            "tasks", List.of(Map.of(
                "key", "parent",
                "type", org.cses.flow.extensions.flow.Parallel.class.getName(),
                "tasks", List.of(
                    Map.of(
                        "key", "left",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                    ),
                    Map.of(
                        "key", "right",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                    )
                )
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        boolean executionChanged = executorService.handle(context);

        assertTrue(executionChanged);
        assertEquals(3, execution.taskRuns().size());
        TaskRun parentRun = execution.taskRuns().getFirst();
        assertTrue(parentRun.state().is(State.Type.RUNNING));
        assertTrue(execution.taskRuns().subList(1, 3).stream()
            .allMatch(taskRun ->
                taskRun.parentId().orElseThrow().equals(parentRun.id())
        ));
        assertTrue(execution.taskRuns().subList(1, 3).stream()
            .allMatch(taskRun ->
                taskRun.state().is(State.Type.CREATED)
            ));
        assertEquals(
            List.of("left", "right"),
            context.workerTasks().stream()
                .map(WorkerTask::taskRunId)
                .map(execution::requireTaskRun)
                .map(TaskRun::taskId)
                .map(taskId -> flow.findTask(taskId).orElseThrow())
                .map(Task::key)
                .toList()
        );
        assertEquals(2, context.workerTasks().size());
        assertTrue(context.nexts().isEmpty());
        assertTrue(context.pausedTaskRuns().isEmpty());
    }

    @Test
    void handleRunsPauseActionBeforePausingOnlyThePauseRun() {
        Flow flow = deploy(Map.of(
            "key", "pause-branch",
            "tasks", List.of(Map.of(
                "key", "wait-confirmation",
                "type", org.cses.flow.extensions.flow.Pause.class.getName(),
                "pause", Map.of(
                    "key", "create-confirmation",
                    "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName(),
                    "route", "outputs.never == \"selected\""
                )
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        boolean executionChanged = executorService.handle(context);

        assertTrue(executionChanged);
        assertTrue(execution.state().is(State.Type.RUNNING));
        assertEquals(2, execution.taskRuns().size());
        assertTrue(execution.taskRuns().getFirst().state().is(
            State.Type.RUNNING
        ));
        assertEquals(1, context.workerTasks().size());
        assertEquals(
            "create-confirmation",
            task(context.workerTasks().getFirst(), execution, flow).key()
        );
        assertTrue(context.pausedTaskRuns().isEmpty());

        WorkerTask action = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, action);
        executorService.applyResult(
            context,
            WorkerTaskResult.completed(action, Map.of())
        );
        executorService.handle(context);

        assertTrue(execution.taskRuns().getFirst().state().is(
            State.Type.PAUSED
        ));
        assertEquals(1, context.pausedTaskRuns().size());
        assertTrue(context.nexts().isEmpty());
    }

    @Test
    void resumeRestoresPauseRunToRunningBeforeExecutorCompletesIt() {
        Flow flow = deploy(Map.of(
            "key", "resume-pause",
            "tasks", List.of(Map.of(
                "key", "wait-confirmation",
                "type", org.cses.flow.extensions.flow.Pause.class.getName(),
                "pause", Map.of(
                    "key", "create-confirmation",
                    "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                ),
                "resume", List.of(Map.of(
                    "key", "decision",
                    "type", "STRING",
                    "required", true
                ))
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        executorService.handle(context);
        WorkerTask action = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, action);
        executorService.applyResult(
            context,
            WorkerTaskResult.completed(action, Map.of())
        );
        executorService.handle(context);
        TaskRun pauseRun = run(execution, flow, "wait-confirmation");
        assertTrue(pauseRun.state().is(State.Type.PAUSED));

        executorService.resume(
            context,
            pauseRun.id(),
            Map.of("decision", "APPROVED")
        );

        assertTrue(pauseRun.state().is(State.Type.RUNNING));
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.PAUSED,
                State.Type.RUNNING
            ),
            pauseRun.state().history().stream()
                .map(State.History::state)
                .toList()
        );

        executorService.handle(context);

        assertTrue(pauseRun.state().is(State.Type.COMPLETED));
        assertEquals(
            Map.of("decision", "APPROVED"),
            pauseRun.outputs()
        );
        assertTrue(execution.state().is(State.Type.COMPLETED));
    }

    @Test
    void handleKeepsParallelAndExecutionRunningWhenChildrenArePaused() {
        Flow flow = deploy(Map.of(
            "key", "parallel-pause-branches",
            "tasks", List.of(Map.of(
                "key", "parallel",
                "type", org.cses.flow.extensions.flow.Parallel.class.getName(),
                "tasks", List.of(
                    Map.of(
                        "key", "left",
                        "type", org.cses.flow.extensions.flow.Pause.class.getName(),
                        "pause", Map.of(
                            "key", "left-action",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                        )
                    ),
                    Map.of(
                        "key", "right",
                        "type", org.cses.flow.extensions.flow.Pause.class.getName(),
                        "pause", Map.of(
                            "key", "right-action",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                        )
                    )
                )
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        boolean executionChanged = executorService.handle(context);

        assertTrue(executionChanged);
        assertTrue(execution.state().is(State.Type.RUNNING));
        assertEquals(5, execution.taskRuns().size());
        assertTrue(execution.taskRuns().getFirst().state().is(
            State.Type.RUNNING
        ));
        assertTrue(execution.taskRuns().subList(1, 3).stream()
            .allMatch(taskRun -> taskRun.state().is(State.Type.RUNNING)));
        assertEquals(2, context.workerTasks().size());
        for (WorkerTask action : context.takeWorkerTasks()) {
            executorService.dispatch(context, action);
            executorService.applyResult(
                context,
                WorkerTaskResult.completed(action, Map.of())
            );
        }

        executorService.handle(context);

        assertTrue(execution.taskRuns().subList(1, 3).stream()
            .allMatch(taskRun -> taskRun.state().is(State.Type.PAUSED)));
        assertEquals(2, context.pausedTaskRuns().size());
        assertTrue(context.nexts().isEmpty());
    }

    @Test
    void parallelScopeCompletesOnlyAfterEverySelectedBranchSettles() {
        Flow flow = deploy(Map.of(
            "key", "parallel-scope",
            "tasks", List.of(
                Map.of(
                    "key", "parallel",
                    "type", org.cses.flow.extensions.flow.Parallel.class.getName(),
                    "tasks", List.of(
                        Map.of(
                            "key", "left",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                        ),
                        Map.of(
                            "key", "right",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                        )
                    )
                ),
                Map.of(
                    "key", "after",
                    "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                )
            )
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        executorService.handle(context);
        List<WorkerTask> branches = context.takeWorkerTasks();
        TaskRun parallel = run(execution, flow, "parallel");

        executorService.dispatch(context, branches.getFirst());
        executorService.applyResult(
            context,
            WorkerTaskResult.completed(branches.getFirst(), Map.of())
        );

        assertTrue(parallel.state().is(State.Type.RUNNING));
        assertTrue(execution.state().is(State.Type.RUNNING));

        executorService.dispatch(context, branches.getLast());
        executorService.applyResult(
            context,
            WorkerTaskResult.completed(branches.getLast(), Map.of())
        );

        assertTrue(parallel.state().is(State.Type.RUNNING));

        executorService.handle(context);

        assertTrue(parallel.state().is(State.Type.COMPLETED));
        assertEquals(1, context.workerTasks().size());
        assertEquals(
            "after",
            task(context.workerTasks().getFirst(), execution, flow).key()
        );
    }

    @Test
    void parallelWithNoSelectedBranchesCompletesNormally() {
        Flow flow = deploy(Map.of(
            "key", "empty-parallel",
            "tasks", List.of(Map.of(
                "key", "parallel",
                "type", org.cses.flow.extensions.flow.Parallel.class.getName()
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        executorService.handle(context);

        assertTrue(execution.state().is(State.Type.COMPLETED));
        assertEquals(1, execution.taskRuns().size());
        assertTrue(execution.taskRuns().getFirst().state().is(
            State.Type.COMPLETED
        ));
        assertTrue(context.workerTasks().isEmpty());
    }

    @Test
    void parallelFansOutIncomingSnapshotAndPassesItThroughAfterJoin() {
        Flow flow = deploy(Map.of(
            "key", "parallel-context",
            "tasks", List.of(Map.of(
                "key", "source",
                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName(),
                "outputs", List.of(Map.of(
                    "key", "decision",
                    "type", "STRING"
                )),
                "tasks", List.of(
                    Map.of(
                        "key", "parallel",
                        "type", org.cses.flow.extensions.flow.Parallel.class.getName(),
                        "inputs", List.of(Map.of(
                            "key", "decision",
                            "type", "STRING"
                        )),
                        "tasks", List.of(
                            Map.of(
                                "key", "left",
                                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName(),
                                "outputs", List.of(Map.of(
                                    "key", "branchResult",
                                    "type", "STRING"
                                ))
                            ),
                            Map.of(
                                "key", "right",
                                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName(),
                                "outputs", List.of(Map.of(
                                    "key", "branchResult",
                                    "type", "STRING"
                                ))
                            )
                        )
                    ),
                    Map.of(
                        "key", "after",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                    )
                )
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        executorService.handle(context);
        WorkerTask source = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, source);
        executorService.applyResult(
            context,
            WorkerTaskResult.completed(
                source,
                Map.of("decision", "approved")
            )
        );

        executorService.handle(context);
        List<WorkerTask> branches = context.takeWorkerTasks();
        assertEquals(2, branches.size());
        assertTrue(branches.stream().allMatch(workerTask ->
            Map.of("decision", "approved").equals(
                workerTask.inputs().get("outputs")
            )
        ));

        for (int index = 0; index < branches.size(); index++) {
            WorkerTask branch = branches.get(index);
            executorService.dispatch(context, branch);
            executorService.applyResult(
                context,
                WorkerTaskResult.completed(
                    branch,
                    Map.of("branchResult", index == 0 ? "L" : "R")
                )
            );
        }

        executorService.handle(context);
        TaskRun parallel = run(execution, flow, "parallel");
        WorkerTask after = context.workerTasks().getFirst();
        assertTrue(parallel.outputs().isEmpty());
        assertEquals(
            Map.of("decision", "approved"),
            after.inputs().get("outputs")
        );
    }

    @Test
    void dependentBranchCascadesToUnselectedWhenRouteCannotMatch() {
        Flow flow = deploy(Map.of(
            "key", "parallel-unselected-dependency",
            "tasks", List.of(Map.of(
                "key", "source",
                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName(),
                "outputs", List.of(Map.of(
                    "key", "decision",
                    "type", "STRING"
                )),
                "tasks", List.of(Map.of(
                    "key", "parallel",
                    "type", org.cses.flow.extensions.flow.Parallel.class.getName(),
                    "inputs", List.of(Map.of(
                        "key", "decision",
                        "type", "STRING"
                    )),
                    "tasks", List.of(
                        Map.of(
                            "key", "selected-only-when-approved",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName(),
                            "route", "outputs.decision == \"approved\""
                        ),
                        Map.of(
                            "key", "dependent",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName(),
                            "dependOn", List.of(
                                "selected-only-when-approved"
                            )
                        )
                    )
                ))
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        executorService.handle(context);
        WorkerTask source = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, source);
        executorService.applyResult(
            context,
            WorkerTaskResult.completed(
                source,
                Map.of("decision", "rejected")
            )
        );

        executorService.handle(context);

        assertTrue(execution.state().is(State.Type.COMPLETED));
        assertEquals(
            List.of("source", "parallel"),
            execution.taskRuns().stream()
                .map(TaskRun::taskId)
                .map(taskId -> flow.findTask(taskId).orElseThrow().key())
                .toList()
        );
    }

    @Test
    void nestedParallelScopesCompleteFromInsideOut() {
        Flow flow = deploy(Map.of(
            "key", "nested-parallel",
            "tasks", List.of(Map.of(
                "key", "outer",
                "type", org.cses.flow.extensions.flow.Parallel.class.getName(),
                "tasks", List.of(
                    Map.of(
                        "key", "inner",
                        "type", org.cses.flow.extensions.flow.Parallel.class.getName(),
                        "tasks", List.of(
                            Map.of(
                                "key", "inner-left",
                                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                            ),
                            Map.of(
                                "key", "inner-right",
                                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                            )
                        )
                    ),
                    Map.of(
                        "key", "outer-sibling",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                    )
                )
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        executorService.handle(context);
        TaskRun outer = run(execution, flow, "outer");
        TaskRun inner = run(execution, flow, "inner");
        assertTrue(outer.state().is(State.Type.RUNNING));
        assertTrue(inner.state().is(State.Type.RUNNING));

        WorkerTask sibling = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, sibling);
        executorService.applyResult(
            context,
            WorkerTaskResult.completed(sibling, Map.of())
        );

        executorService.handle(context);
        List<WorkerTask> innerBranches = context.takeWorkerTasks();
        assertEquals(2, innerBranches.size());
        for (WorkerTask branch : innerBranches) {
            executorService.dispatch(context, branch);
            executorService.applyResult(
                context,
                WorkerTaskResult.completed(branch, Map.of())
            );
        }

        executorService.handle(context);

        assertTrue(execution.state().is(State.Type.COMPLETED));
        assertTrue(inner.state().is(State.Type.COMPLETED));
        assertTrue(outer.state().is(State.Type.COMPLETED));
        assertEquals(
            List.of(
                "outer",
                "inner",
                "outer-sibling",
                "inner-left",
                "inner-right"
            ),
            execution.taskRuns().stream()
                .map(TaskRun::taskId)
                .map(taskId -> flow.findTask(taskId).orElseThrow().key())
                .toList()
        );
    }

    @Test
    void failedParallelBranchTerminatesScopeAndUnfinishedSibling() {
        Flow flow = deploy(Map.of(
            "key", "parallel-fail-fast",
            "tasks", List.of(Map.of(
                "key", "parallel",
                "type", org.cses.flow.extensions.flow.Parallel.class.getName(),
                "tasks", List.of(
                    Map.of(
                        "key", "left",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                    ),
                    Map.of(
                        "key", "right",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                    )
                )
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        executorService.handle(context);
        List<WorkerTask> branches = context.takeWorkerTasks();
        WorkerTask failedBranch = branches.getFirst();
        executorService.dispatch(context, failedBranch);
        executorService.applyResult(
            context,
            WorkerTaskResult.failed(failedBranch, "branch failed")
        );

        assertTrue(execution.state().is(State.Type.TERMINATED));
        assertTrue(run(execution, flow, "parallel").state().is(
            State.Type.TERMINATED
        ));
        assertTrue(run(execution, flow, "left").state().is(
            State.Type.TERMINATED
        ));
        assertTrue(run(execution, flow, "right").state().is(
            State.Type.TERMINATED
        ));
        assertEquals(
            "branch failed",
            run(execution, flow, "left").error().orElseThrow()
        );
    }

    private static Task task(
        WorkerTask workerTask,
        Execution execution,
        Flow flow
    ) {
        return flow.findTask(
            execution.requireTaskRun(workerTask.taskRunId()).taskId()
        ).orElseThrow();
    }

    private static TaskRun run(
        Execution execution,
        Flow flow,
        String taskKey
    ) {
        Task task = flow.allTasks().stream()
            .filter(candidate -> candidate.key().equals(taskKey))
            .findFirst()
            .orElseThrow();
        return execution.latestTaskRunForTask(task.id()).orElseThrow();
    }

    private static Execution execution(Flow flow) {
        return Execution.create(
            flow.companyId(),
            flow.id(),
            flow.reversion()
        );
    }

    private static Flow deploy(Map<String, ?> definition) {
        return PLUGINS.deploy(
            "executor-company",
            "executor-flow",
            definition,
            null,
            ActorRef.create("executor-user", "Executor User"),
            1_785_312_000_000L
        );
    }

    private static Flow flowWith(Task task) {
        ActorRef actor = ActorRef.create(
            "executor-user",
            "Executor User"
        );
        return Flow.rehydrate(
            "executor-flow",
            "executor-company",
            "invalid-capability",
            1,
            "Invalid runtime capability fixture",
            List.of(),
            List.of(),
            List.of(task),
            false,
            actor,
            actor,
            null,
            1_785_312_000_000L,
            1_785_312_000_000L,
            null
        );
    }

    @SuperBuilder
    @NoArgsConstructor
    private static class NoCapabilityTask extends Task {
    }

    @SuperBuilder
    @NoArgsConstructor
    private static final class ConflictingCapabilityTask
        extends NoCapabilityTask implements RunnableTask, OrchestrationTask {

        @Override
        public RunResult run(RunContext context) {
            return RunResult.completed(Map.of());
        }
    }
}
