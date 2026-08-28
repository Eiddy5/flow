package org.cses.flow.executor;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskResult;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutorServiceTest {

    private static final Context PLUGINS = builtInContext();
    private final ExecutorService executorService = new ExecutorService();

    @Test
    void processFormsTheCompleteRunnableBatchInOneCycle() {
        Flow flow = deploy(Map.of(
            "key", "process-cycle",
            "tasks", List.of(Map.of(
                "key", "prepare",
                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        ExecutorContext processed = executorService.process(context);

        assertSame(context, processed);
        assertTrue(execution.state().is(State.Type.RUNNING));
        assertEquals(1, execution.taskRuns().size());
        assertTrue(execution.taskRuns().getFirst().state().is(
            State.Type.CREATED
        ));
        assertTrue(context.nexts().isEmpty());
        assertEquals(1, context.workerTasks().size());
        assertEquals(
            List.of(State.Type.CREATED, State.Type.RUNNING),
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

        assertThrows(
            IllegalStateException.class,
            () -> executorService.process(context)
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

        assertThrows(
            IllegalStateException.class,
            () -> executorService.process(context)
        );

        assertTrue(execution.state().is(State.Type.CREATED));
        assertTrue(execution.taskRuns().isEmpty());
    }

    @Test
    void processStagesOnlyFirstRunnableChildForSequence() {
        Flow flow = deploy(Map.of(
            "key", "serial-nexts",
            "tasks", List.of(Map.of(
                "key", "parent",
                "type", org.cses.flow.extensions.flow.Sequence.class.getName(),
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

        processUntilBoundary(context);

        assertEquals(
            List.of("first"),
            context.workerTasks().stream()
                .map(workerTask -> task(workerTask, execution, flow).key())
                .toList()
        );
    }

    @Test
    void processKeepsParallelScopeRunningAndStagesChildrenAsOneBatch() {
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

        processUntilBoundary(context);

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
        assertTrue(execution.pausedTaskRuns().isEmpty());
    }

    @Test
    void parallelBranchesUsePersistedConfirmedFlowInputs() {
        Flow flow = deploy(Map.of(
            "key", "parallel-input-route",
            "inputs", List.of(Map.of(
                "key", "amount",
                "type", "DOUBLE",
                "displayName", "Amount",
                "required", true
            )),
            "tasks", List.of(Map.of(
                "key", "parallel",
                "type", org.cses.flow.extensions.flow.Parallel.class.getName(),
                "tasks", List.of(
                    Map.of(
                        "key", "high-value-route",
                        "type", org.cses.flow.extensions.flow.Route.class.getName(),
                        "route", "{{ inputs.amount }} > 1000",
                        "tasks", List.of(Map.of(
                            "key", "high-value",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                        ))
                    ),
                    Map.of(
                        "key", "standard-route",
                        "type", org.cses.flow.extensions.flow.Route.class.getName(),
                        "route", "{{ inputs.amount }} <= 1000",
                        "tasks", List.of(Map.of(
                            "key", "standard",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                        ))
                    )
                )
            ))
        ));
        Execution seed = execution(flow);
        Execution execution = Execution.create(
            seed.id(),
            session(seed.companyId()),
            seed.flowKey(),
            seed.flowVersion(),
            flow.normalizeInputs(Map.of("amount", 1200))
        );
        ExecutorContext context = new ExecutorContext(flow, execution);

        processUntilBoundary(context);

        assertEquals(
            List.of("high-value"),
            context.workerTasks().stream()
                .map(workerTask -> task(workerTask, execution, flow).key())
                .toList()
        );
        assertTrue(execution.taskRuns().stream().allMatch(taskRun ->
            !taskRun.inputs().containsKey("flowInputs")
        ));
        assertEquals(Map.of("amount", 1200.0), execution.inputs());
        assertEquals(
            Map.of("amount", 1200.0),
            execution.inputs()
        );
    }

    @Test
    void parallelBranchesUseFlowLevelVariablesAndExposeThemToWorkers() {
        Flow flow = deploy(Map.of(
            "key", "parallel-variable-route",
            "variables", Map.of("environment", "prod"),
            "tasks", List.of(Map.of(
                "key", "parallel",
                "type", org.cses.flow.extensions.flow.Parallel.class.getName(),
                "tasks", List.of(
                    Map.of(
                        "key", "production-route",
                        "type", org.cses.flow.extensions.flow.Route.class.getName(),
                        "route", "{{ variables.environment }} == prod",
                        "tasks", List.of(Map.of(
                            "key", "production",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                        ))
                    ),
                    Map.of(
                        "key", "staging-route",
                        "type", org.cses.flow.extensions.flow.Route.class.getName(),
                        "route", "{{ variables.environment }} == staging",
                        "tasks", List.of(Map.of(
                            "key", "staging",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                        ))
                    )
                )
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        processUntilBoundary(context);

        assertEquals(
            List.of("production"),
            context.workerTasks().stream()
                .map(workerTask -> task(workerTask, execution, flow).key())
                .toList()
        );
        assertEquals(
            flow.variables(),
            context.workerTasks().getFirst().variables().get(
                RunContext.FLOW_VARIABLES_VARIABLE
            )
        );
    }

    @Test
    void routeReadsPrecedingOutputsAndEvaluatesACompoundCondition() {
        Flow flow = deploy(Map.of(
            "key", "output-route",
            "variables", Map.of("environment", "prod"),
            "inputs", List.of(Map.of(
                "key", "override",
                "type", "BOOLEAN",
                "required", false,
                "defaultValue", false
            )),
            "tasks", List.of(Map.of(
                "key", "sequence",
                "type", org.cses.flow.extensions.flow.Sequence.class.getName(),
                "tasks", List.of(
                    Map.of(
                        "key", "prepare",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName(),
                        "outputs", List.of(Map.of(
                            "key", "decision",
                            "type", "STRING"
                        ))
                    ),
                    Map.of(
                        "key", "approved-route",
                        "type", org.cses.flow.extensions.flow.Route.class.getName(),
                        "route", "({{ outputs.prepare.decision }} == approved "
                            + "&& {{ variables.environment }} == prod) "
                            + "|| {{ inputs.override }} == true",
                        "tasks", List.of(Map.of(
                            "key", "approved",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                        ))
                    ),
                    Map.of(
                        "key", "rejected-route",
                        "type", org.cses.flow.extensions.flow.Route.class.getName(),
                        "route", "{{ outputs.prepare.decision }} == rejected",
                        "tasks", List.of(Map.of(
                            "key", "rejected",
                            "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                        ))
                    )
                )
            ))
        ));
        Execution seed = execution(flow);
        Execution execution = Execution.create(
            seed.id(),
            session(seed.companyId()),
            seed.flowKey(),
            seed.flowVersion(),
            flow.normalizeInputs(Map.of())
        );
        ExecutorContext context = new ExecutorContext(flow, execution);

        completeNextWorker(context, Map.of("decision", "approved"));
        processUntilBoundary(context);

        assertEquals(
            List.of("approved"),
            context.workerTasks().stream()
                .map(workerTask -> task(workerTask, execution, flow).key())
                .toList()
        );
        assertTrue(execution.taskRuns().stream()
            .map(TaskRun::taskId)
            .map(taskId -> flow.findTask(taskId).orElseThrow().key())
            .noneMatch("rejected-route"::equals));
    }

    @Test
    void unmatchedRouteSettlesWithoutCreatingItsTaskRun() {
        Flow flow = deploy(Map.of(
            "key", "unmatched-route",
            "variables", Map.of("environment", "staging"),
            "tasks", List.of(Map.of(
                "key", "route",
                "type", org.cses.flow.extensions.flow.Route.class.getName(),
                "route", "{{ variables.environment }} == prod",
                "tasks", List.of(Map.of(
                    "key", "never",
                    "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                ))
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        processUntilBoundary(context);

        assertTrue(execution.state().is(State.Type.SUCCESS));
        assertTrue(execution.taskRuns().isEmpty());
    }

    @Test
    void processRunsPauseActionBeforePausingOnlyThePauseRun() {
        Flow flow = deploy(Map.of(
            "key", "pause-branch",
            "tasks", List.of(Map.of(
                "key", "wait-confirmation",
                "type", org.cses.flow.extensions.flow.Pause.class.getName(),
                "pause", Map.of(
                    "key", "create-confirmation",
                    "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                )
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        processUntilBoundary(context);

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
        assertTrue(execution.pausedTaskRuns().isEmpty());

        WorkerTask action = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, action);
        executorService.applyResult(
            context,
            WorkerTaskResult.success(action, Map.of())
        );
        processUntilBoundary(context);

        assertTrue(execution.taskRuns().getFirst().state().is(
            State.Type.PAUSED
        ));
        assertTrue(execution.state().is(State.Type.PAUSED));
        assertEquals(1, execution.pausedTaskRuns().size());
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

        processUntilBoundary(context);
        WorkerTask action = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, action);
        executorService.applyResult(
            context,
            WorkerTaskResult.success(action, Map.of())
        );
        processUntilBoundary(context);
        TaskRun pauseRun = run(execution, flow, "wait-confirmation");
        assertTrue(pauseRun.state().is(State.Type.PAUSED));

        executorService.resume(
            context,
            pauseRun.id(),
            Map.of("decision", "APPROVED")
        );

        assertTrue(execution.state().is(State.Type.RESTARTED));
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

        processUntilBoundary(context);

        assertTrue(pauseRun.state().is(State.Type.SUCCESS));
        assertEquals(
            Map.of("decision", "APPROVED"),
            pauseRun.outputs()
        );
        assertTrue(execution.state().is(State.Type.SUCCESS));
    }

    @Test
    void processPausesExecutionAfterEveryActiveBranchPauses() {
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

        processUntilBoundary(context);

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
                WorkerTaskResult.success(action, Map.of())
            );
        }

        processUntilBoundary(context);

        assertTrue(execution.taskRuns().subList(1, 3).stream()
            .allMatch(taskRun -> taskRun.state().is(State.Type.PAUSED)));
        assertTrue(execution.taskRuns().getFirst().state().is(
            State.Type.PAUSED
        ));
        assertTrue(execution.state().is(State.Type.PAUSED));
        assertEquals(3, execution.pausedTaskRuns().size());
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

        processUntilBoundary(context);
        List<WorkerTask> branches = context.takeWorkerTasks();
        TaskRun parallel = run(execution, flow, "parallel");

        executorService.dispatch(context, branches.getFirst());
        executorService.applyResult(
            context,
            WorkerTaskResult.success(branches.getFirst(), Map.of())
        );

        assertTrue(parallel.state().is(State.Type.RUNNING));
        assertTrue(execution.state().is(State.Type.RUNNING));

        executorService.dispatch(context, branches.getLast());
        executorService.applyResult(
            context,
            WorkerTaskResult.success(branches.getLast(), Map.of())
        );

        assertTrue(parallel.state().is(State.Type.RUNNING));

        processUntilBoundary(context);

        assertTrue(parallel.state().is(State.Type.SUCCESS));
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

        processUntilBoundary(context);

        assertTrue(execution.state().is(State.Type.SUCCESS));
        assertEquals(1, execution.taskRuns().size());
        assertTrue(execution.taskRuns().getFirst().state().is(
            State.Type.SUCCESS
        ));
        assertTrue(context.workerTasks().isEmpty());
    }

    @Test
    void parallelFansOutIncomingSnapshotAndPassesItThroughAfterJoin() {
        Flow flow = deploy(Map.of(
            "key", "parallel-context",
            "tasks", List.of(Map.of(
                "key", "serial",
                "type", org.cses.flow.extensions.flow.Sequence.class.getName(),
                "tasks", List.of(
                    Map.of(
                        "key", "source",
                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName(),
                        "outputs", List.of(Map.of(
                            "key", "decision",
                            "type", "STRING"
                        ))
                    ),
                    Map.of(
                        "key", "parallel",
                        "type", org.cses.flow.extensions.flow.Parallel.class.getName(),
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

        processUntilBoundary(context);
        WorkerTask source = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, source);
        executorService.applyResult(
            context,
            WorkerTaskResult.success(
                source,
                Map.of("decision", "approved")
            )
        );

        processUntilBoundary(context);
        List<WorkerTask> branches = context.takeWorkerTasks();
        assertEquals(2, branches.size());
        assertTrue(branches.stream().allMatch(workerTask ->
            Map.of("source", Map.of("decision", "approved")).equals(
                workerTask.taskInputs().get("outputs")
            )
        ));

        for (int index = 0; index < branches.size(); index++) {
            WorkerTask branch = branches.get(index);
            executorService.dispatch(context, branch);
            executorService.applyResult(
                context,
                WorkerTaskResult.success(
                    branch,
                    Map.of("branchResult", index == 0 ? "L" : "R")
                )
            );
        }

        processUntilBoundary(context);
        TaskRun parallel = run(execution, flow, "parallel");
        WorkerTask after = context.workerTasks().getFirst();
        assertTrue(parallel.outputs().isEmpty());
        assertEquals(
            Map.of(
                "source", Map.of("decision", "approved"),
                "parallel", Map.of()
            ),
            after.taskInputs().get("outputs")
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

        processUntilBoundary(context);
        TaskRun outer = run(execution, flow, "outer");
        TaskRun inner = run(execution, flow, "inner");
        assertTrue(outer.state().is(State.Type.RUNNING));
        assertTrue(inner.state().is(State.Type.RUNNING));

        WorkerTask sibling = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, sibling);
        executorService.applyResult(
            context,
            WorkerTaskResult.success(sibling, Map.of())
        );

        processUntilBoundary(context);
        List<WorkerTask> innerBranches = context.takeWorkerTasks();
        assertEquals(2, innerBranches.size());
        for (WorkerTask branch : innerBranches) {
            executorService.dispatch(context, branch);
            executorService.applyResult(
                context,
                WorkerTaskResult.success(branch, Map.of())
            );
        }

        processUntilBoundary(context);

        assertTrue(execution.state().is(State.Type.SUCCESS));
        assertTrue(inner.state().is(State.Type.SUCCESS));
        assertTrue(outer.state().is(State.Type.SUCCESS));
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
    void warningTaskRunLetsTheFlowSettleAsWarning() {
        Flow flow = deploy(Map.of(
            "key", "warning-flow",
            "tasks", List.of(Map.of(
                "key", "warn",
                "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        processUntilBoundary(context);
        WorkerTask workerTask = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, workerTask);
        executorService.applyResult(
            context,
            WorkerTaskResult.warning(workerTask, Map.of())
        );

        processUntilBoundary(context);

        assertTrue(execution.state().is(State.Type.WARNING));
        assertTrue(execution.taskRuns().getFirst().state().is(
            State.Type.WARNING
        ));
    }

    @Test
    void killingConvergesEveryUnfinishedTaskRunBeforeExecution() {
        Flow flow = deploy(Map.of(
            "key", "killing-flow",
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

        processUntilBoundary(context);
        context.takeWorkerTasks();
        executorService.kill(context);

        assertTrue(execution.state().is(State.Type.KILLING));

        executorService.process(context);

        assertTrue(execution.state().is(State.Type.KILLED));
        assertTrue(execution.taskRuns().stream().allMatch(taskRun ->
            taskRun.state().is(State.Type.KILLED)
        ));
        assertEquals(
            List.of(
                State.Type.CREATED,
                State.Type.RUNNING,
                State.Type.KILLING,
                State.Type.KILLED
            ),
            execution.state().history().stream()
                .map(State.History::state)
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

        processUntilBoundary(context);
        List<WorkerTask> branches = context.takeWorkerTasks();
        WorkerTask failedBranch = branches.getFirst();
        executorService.dispatch(context, failedBranch);
        executorService.applyResult(
            context,
            WorkerTaskResult.failed(failedBranch, "branch failed")
        );

        assertTrue(execution.state().is(State.Type.FAILED));
        assertTrue(run(execution, flow, "parallel").state().is(
            State.Type.KILLED
        ));
        assertTrue(run(execution, flow, "left").state().is(
            State.Type.FAILED
        ));
        assertTrue(run(execution, flow, "right").state().is(
            State.Type.KILLED
        ));
        assertEquals(
            "branch failed",
            run(execution, flow, "left").error().orElseThrow()
        );
    }

    @Test
    void fixedLoopCreatesOneRecoverableTaskRunPerIteration() {
        Flow flow = deploy(Map.of(
            "key", "fixed-loop",
            "tasks", List.of(Map.of(
                "key", "repeat",
                "type", org.cses.flow.extensions.flow.Loop.class.getName(),
                "times", 3,
                "tasks", List.of(Map.of(
                    "key", "work",
                    "type", org.cses.flow.extensions.tasks.AutomaticTask.class
                        .getName()
                ))
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        for (int iteration = 1; iteration <= 3; iteration++) {
            processUntilBoundary(context);
            WorkerTask workerTask = context.takeWorkerTasks().getFirst();
            TaskRun taskRun = execution.requireTaskRun(
                workerTask.taskRunId()
            );
            assertEquals(iteration, taskRun.iteration().orElseThrow());
            assertEquals(
                Map.of("iteration", iteration),
                workerTask.taskInputs().get("loop")
            );
            executorService.dispatch(context, workerTask);
            executorService.applyResult(
                context,
                WorkerTaskResult.success(workerTask, Map.of())
            );
            if (iteration < 3) {
                execution = execution.copy();
                context = new ExecutorContext(flow, execution);
            }
        }

        processUntilBoundary(context);

        assertTrue(execution.state().is(State.Type.SUCCESS));
        assertTrue(run(execution, flow, "repeat").state().is(
            State.Type.SUCCESS
        ));
        Task work = flow.allTasks().stream()
            .filter(task -> task.key().equals("work"))
            .findFirst()
            .orElseThrow();
        assertEquals(
            List.of(1, 2, 3),
            execution.taskRunsForTask(work.id()).stream()
                .map(taskRun -> taskRun.iteration().orElseThrow())
                .toList()
        );
    }

    @Test
    void loopUntilStopsWhenTheCurrentIterationConditionMatches() {
        Flow flow = loopUntilFlow(3);
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        completeNextWorker(context, Map.of("status", "WAIT"));
        completeNextWorker(context, Map.of("status", "DONE"));
        processUntilBoundary(context);

        assertTrue(execution.state().is(State.Type.SUCCESS));
        assertTrue(run(execution, flow, "poll").state().is(
            State.Type.SUCCESS
        ));
        Task check = flow.allTasks().stream()
            .filter(task -> task.key().equals("check"))
            .findFirst()
            .orElseThrow();
        assertEquals(2, execution.taskRunsForTask(check.id()).size());
    }

    @Test
    void loopUntilFailsWhenItsMaximumIterationRemainsUnsatisfied() {
        Flow flow = loopUntilFlow(2);
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        completeNextWorker(context, Map.of("status", "WAIT"));
        completeNextWorker(context, Map.of("status", "WAIT"));
        processUntilBoundary(context);

        assertTrue(execution.state().is(State.Type.FAILED));
        TaskRun loopRun = run(execution, flow, "poll");
        assertTrue(loopRun.state().is(State.Type.FAILED));
        assertTrue(loopRun.error().orElseThrow().contains(
            "after 2 iterations"
        ));
    }

    private void completeNextWorker(
        ExecutorContext context,
        Map<String, ?> outputs
    ) {
        processUntilBoundary(context);
        WorkerTask workerTask = context.takeWorkerTasks().getFirst();
        executorService.dispatch(context, workerTask);
        executorService.applyResult(
            context,
            WorkerTaskResult.success(workerTask, outputs)
        );
    }

    private static Flow loopUntilFlow(int maxIterations) {
        return deploy(Map.of(
            "key", "loop-until",
            "tasks", List.of(Map.of(
                "key", "poll",
                "type", org.cses.flow.extensions.flow.LoopUntil.class
                    .getName(),
                "condition", "{{ outputs.check.status }} == DONE",
                "maxIterations", maxIterations,
                "tasks", List.of(Map.of(
                    "key", "check",
                    "type", org.cses.flow.extensions.tasks.AutomaticTask.class
                        .getName(),
                    "outputs", List.of(Map.of(
                        "key", "status",
                        "type", "STRING"
                    ))
                ))
            ))
        ));
    }

    private void processUntilBoundary(ExecutorContext context) {
        for (int cycle = 0; cycle < 100; cycle++) {
            int taskRunCount = context.execution().taskRuns().size();
            int transitionCount = transitionCount(context.execution());

            executorService.process(context);

            if (!context.workerTasks().isEmpty()
                || context.execution().state().isTerminal()
                || context.execution().state().is(State.Type.PAUSED)) {
                return;
            }
            if (taskRunCount == context.execution().taskRuns().size()
                && transitionCount == transitionCount(context.execution())) {
                return;
            }
        }
        throw new AssertionError("Executor did not reach a cycle boundary");
    }

    private static int transitionCount(Execution execution) {
        return execution.state().history().size()
            + execution.taskRuns().stream()
            .mapToInt(taskRun -> taskRun.state().history().size())
            .sum();
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
            null,
            session(flow.companyId()),
            flow.key(),
            flow.reversion(),
            Map.of()
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
            "invalid-capability-flow-id",
            "executor-company",
            "invalid-capability",
            false,
            1L,
            "Invalid runtime capability fixture",
            Map.of(),
            List.of(),
            List.of(),
            List.of(task),
            org.paas.session.RecordState.Open,
            actor,
            actor,
            null,
            1_785_312_000_000L,
            1_785_312_000_000L,
            null,
            "key: invalid-capability"
        );
    }

    private static Session<User> session(String companyId) {
        User user = new User();
        user.setId("executor-user");
        user.setName("Executor User");
        user.setCompanyId(companyId);

        Session<User> session = new Session<>();
        session.setCompanyId(companyId);
        session.setUser(user);
        return session;
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
            return RunResult.success(Map.of());
        }
    }
}
