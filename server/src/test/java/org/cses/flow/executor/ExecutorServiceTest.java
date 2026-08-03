package org.cses.flow.executor;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.BranchTask;
import org.cses.flow.core.domains.tasks.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.cses.flow.core.plugins.TaskExtensionTestSupport.builtInDispatcher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutorServiceTest {

    private final ExecutorService executorService = new ExecutorService();

    @Test
    void contextContainsOnlyExecutorRuntimeData() {
        assertEquals(
            Set.of(
                "execution",
                "flow",
                "nexts",
                "workerTasks",
                "branchTaskRuns",
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
                "type", "AUTO"
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
                "type", "AUTO"
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
                "type", "AUTO"
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
        Flow flow = flowWith(new NoCapabilityTask());
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
        Flow flow = flowWith(new ConflictingCapabilityTask());
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
                "type", "AUTO",
                "tasks", List.of(
                    Map.of(
                        "key", "first",
                        "type", "AUTO"
                    ),
                    Map.of(
                        "key", "second",
                        "type", "AUTO"
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
    void handleNextStagesAllRunnableParallelChildrenAsOneBatch() {
        Flow flow = deploy(Map.of(
            "key", "parallel-nexts",
            "tasks", List.of(Map.of(
                "key", "parent",
                "type", "PARALLEL",
                "tasks", List.of(
                    Map.of(
                        "key", "left",
                        "type", "AUTO"
                    ),
                    Map.of(
                        "key", "right",
                        "type", "AUTO"
                    )
                )
            ))
        ));
        Execution execution = execution(flow);
        ExecutorContext context = new ExecutorContext(flow, execution);

        executorService.handleNext(context);
        executorService.onNexts(context);
        assertTrue(context.workerTasks().isEmpty());
        TaskRun parent = context.takeBranchTaskRuns().getFirst();
        executorService.dispatchBranch(context, parent);

        executorService.handleNext(context);

        assertEquals(1, execution.taskRuns().size());
        assertEquals(
            List.of("left", "right"),
            context.nexts().stream()
                .map(TaskRun::taskId)
                .map(taskId -> flow.findTask(taskId).orElseThrow())
                .map(task -> task.key())
                .toList()
        );

        executorService.onNexts(context);

        assertEquals(3, execution.taskRuns().size());
        TaskRun parentRun = execution.taskRuns().getFirst();
        assertTrue(execution.taskRuns().subList(1, 3).stream()
            .allMatch(taskRun ->
                taskRun.parentId().orElseThrow().equals(parentRun.id())
        ));
        assertEquals(2, context.workerTasks().size());
        assertTrue(context.nexts().isEmpty());
    }

    private static Execution execution(Flow flow) {
        return Execution.create(
            flow.companyId(),
            flow.id(),
            flow.reversion()
        );
    }

    private static Flow deploy(Map<String, ?> definition) {
        return Flow.deploy(
            "executor-company",
            "executor-flow",
            definition,
            null,
            builtInDispatcher(),
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

    private static class NoCapabilityTask extends Task {

        protected NoCapabilityTask() {
            super(
                "invalid-task",
                null,
                "invalid-task",
                "INVALID",
                List.of(),
                List.of(),
                RouteExpression.direct(),
                List.of(),
                List.of()
            );
        }
    }

    private static final class ConflictingCapabilityTask
        extends NoCapabilityTask implements RunnableTask, BranchTask {

        @Override
        public RunResult run(RunContext context) {
            return RunResult.completed(Map.of());
        }
    }
}
