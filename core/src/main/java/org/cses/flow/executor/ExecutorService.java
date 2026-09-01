package org.cses.flow.executor;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.conditions.ConditionContext;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.Generation;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.runner.RunVariables;
import org.cses.flow.extensions.flow.Branch;
import org.cses.flow.extensions.flow.Loop;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.flow.Route;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Internal state-machine implementation for one {@link ExecutorContext}.
 *
 * <p>{@link #process(ExecutorContext)} advances one deterministic scheduling
 * cycle. The caller owns persistence, Worker invocation, and any subsequent
 * cycle.</p>
 */
@Singleton
public final class ExecutorService {

    public ExecutorContext process(ExecutorContext context) {
        java.util.Objects.requireNonNull(context, "context");
        if (!context.workerTasks().isEmpty()) {
            throw new IllegalStateException("Pending WorkerTasks must be consumed before processing");
        }

        if (!context.canBeProcessed()) {
            return context;
        }

        context = this.handleRestart(context);
        handleKillingTaskRuns(context);
        handleKilling(context);

        if (context.canScheduleNext()) {
            handleNext(context);
            handleWorkerTasks(context);
            handleOrchestrationTasks(context);
        }

        handleEnd(context);
        return context;
    }

    private ExecutorContext handleRestart(ExecutorContext executor) {
        if (!executor.execution().state().is(State.Type.RESTARTED)) {
            return executor;
        }
        return executor.restart(executor.execution().restart());
    }

    private static void handleKillingTaskRuns(ExecutorContext context) {
        if (!context.execution().state().is(State.Type.KILLING)) {
            return;
        }
        context.execution().killUnfinishedTaskRuns();
        context.captureState();
    }

    private static void handleKilling(ExecutorContext context) {
        Execution execution = context.execution();
        if (!execution.state().is(State.Type.KILLING) || !execution.unfinishedTaskRuns().isEmpty()) {
            return;
        }
        execution.finishKilling();
        context.captureState();
    }

    private static void handleNext(ExecutorContext context) {
        Execution execution = context.execution();
        if (!execution.state().is(State.Type.CREATED) && !execution.state().is(State.Type.RUNNING)) {
            throw new WorkflowException("Execution cannot plan work from " + execution.state().current() + ": " + execution.id());
        }

        SearchResult result = searchTopLevel(context, context.flow().tasks());
        context.stageNexts(result.nexts());
        context.stageOrchestrationCompletions(result.orchestrationCompletions());
        List<TaskRun> nexts = context.nexts();
        requireRuntimeCapabilities(context, nexts);
        List<TaskRun> unattached = nexts.stream().filter(taskRun -> execution.findTaskRun(taskRun.id()).isEmpty()).toList();

        if (execution.state().is(State.Type.CREATED) && !unattached.isEmpty()) {
            if (unattached.size() != nexts.size()) {
                throw new IllegalStateException("A CREATED Execution cannot contain TaskRuns");
            }
            execution.startWithTaskRuns(unattached);
        } else if (execution.state().is(State.Type.CREATED)) {
            requireStartableEmptyPlan(context);
            execution.start();
        } else if (!unattached.isEmpty()) {
            execution.addTaskRuns(unattached);
        } else {
            return;
        }
        context.captureState();
    }

    private static void handleWorkerTasks(ExecutorContext context) {
        List<WorkerTask> workerTasks = new ArrayList<>();
        for (TaskRun taskRun : context.nexts()) {
            Task task = requireTask(context, taskRun);
            if (task instanceof RunnableTask) {
                workerTasks.add(workerTask(context, taskRun, task));
            }
        }
        context.stageWorkerTasks(workerTasks);
    }

    private static WorkerTask workerTask(
        ExecutorContext context,
        TaskRun taskRun,
        Task task
    ) {
        Map<String, Object> variables = RunVariables.builder()
            .flow(context.flow())
            .execution(context.execution())
            .task(task)
            .taskRun(taskRun)
            .build();
        return WorkerTask.from(
            context.execution().id(),
            taskRun.id(),
            taskRun.parentId().orElse(null),
            task,
            variables
        );
    }

    private static void handleOrchestrationTasks(ExecutorContext context) {
        for (TaskRun taskRun : context.nexts()) {
            Task task = requireTask(context, taskRun);
            if (task instanceof OrchestrationTask) {
                handleOrchestration(context, taskRun);
            }
        }
        for (String taskRunId : context.orchestrationCompletions()) {
            completeOrchestrationScope(context, taskRunId);
        }
        context.clearNexts();
        context.takeOrchestrationCompletions();
    }

    public WorkerTask dispatch(
        ExecutorContext context,
        WorkerTask workerTask
    ) {
        requireExecution(context, workerTask.executionId());
        context.execution().startTaskRun(workerTask.taskRunId());
        context.captureState();
        TaskRun runningTaskRun = context.execution().requireTaskRun(
            workerTask.taskRunId()
        );
        Task task = requireTask(context, runningTaskRun);
        return workerTask(context, runningTaskRun, task);
    }

    private static void handleOrchestration(ExecutorContext context, TaskRun plannedTaskRun) {
        TaskRun taskRun = context.execution().requireTaskRun(plannedTaskRun.id());
        if (!taskRun.state().is(State.Type.CREATED)) {
            throw new IllegalStateException("Only a CREATED Orchestration TaskRun can be handled: " + taskRun.id());
        }
        Task task = context.flow().findTask(taskRun.taskId()).orElseThrow(() -> new IllegalStateException("TaskRun references a missing Task: " + taskRun.taskId()));
        if (!(task instanceof OrchestrationTask orchestrationTask) || task instanceof RunnableTask) {
            throw new IllegalStateException("Orchestration handling requires only the " + "OrchestrationTask capability: " + task.getType());
        }

        Execution execution = context.execution();
        execution.startTaskRun(taskRun.id());
        if (orchestrationTask.iteratesChildren()) {
            execution.startTaskRunGeneration(taskRun.id(), "INITIAL");
        }
        if (task instanceof Route route
            && !matchesRouteCondition(context, route, taskRun)) {
            execution.skipTaskRun(taskRun.id());
            context.captureState();
            return;
        }
        boolean pausesTaskRun = orchestrationTask.pausesTaskRun();
        boolean holdsScope = orchestrationTask.holdsTaskRunUntilChildrenSettle();
        if (pausesTaskRun && holdsScope) {
            throw new IllegalStateException("An OrchestrationTask cannot pause and hold a child scope: " + task.getType());
        }
        if (!pausesTaskRun && !holdsScope) {
            execution.succeedTaskRun(taskRun.id(), task.validateOutputs(Map.of()));
        }
        context.captureState();
    }

    private static void completeOrchestrationScope(ExecutorContext context, String taskRunId) {
        TaskRun taskRun = context.execution().requireTaskRun(taskRunId);
        if (!taskRun.state().is(State.Type.RUNNING)) {
            throw new IllegalStateException("Only a RUNNING orchestration scope can complete: " + taskRun.id());
        }
        Task task = context.flow().findTask(taskRun.taskId()).orElseThrow(() -> new IllegalStateException("TaskRun references a missing Task: " + taskRun.taskId()));
        if (!(task instanceof OrchestrationTask orchestrationTask) || (!orchestrationTask.holdsTaskRunUntilChildrenSettle() && !orchestrationTask.pausesTaskRun())) {
            throw new IllegalStateException("TaskRun is not an orchestration scope: " + taskRun.id());
        }
        if (orchestrationTask.pausesTaskRun()) {
            if (hasPaused(taskRun)) {
                context.execution().succeedTaskRun(taskRun.id(), taskRun.outputs());
            } else {
                context.execution().pauseTaskRun(taskRun.id());
            }
            context.captureState();
            return;
        }
        if (orchestrationTask.iteratesChildren()) {
            int iteration = taskRun.generation().current()
                .orElseThrow(() -> new IllegalStateException(
                    "Loop scope has no current Generation: " + taskRun.id()
                ))
                .version();
            OrchestrationTask.IterationDecision decision =
                decideAfterIteration(
                    context,
                    task,
                    orchestrationTask,
                    taskRun,
                    iteration,
                    iterationOutputs(
                        context,
                        task,
                        IterationScope.from(
                            task,
                            taskRun.id(),
                            iteration,
                            null
                        )
                    )
            );
            if (decision == OrchestrationTask.IterationDecision.SUCCESS) {
                context.execution().completeTaskRunGeneration(taskRun.id());
                context.execution().succeedTaskRun(
                    taskRun.id(),
                    task.validateOutputs(Map.of())
                );
            } else if (
                decision == OrchestrationTask.IterationDecision.FAILURE
            ) {
                context.execution().completeTaskRunGeneration(taskRun.id());
                context.execution().failTaskRun(
                    taskRun.id(),
                    orchestrationTask.iterationFailureMessage(iteration)
                );
            } else {
                throw new IllegalStateException(
                    "Loop scope requested completion before its next "
                        + "iteration: " + taskRun.id()
                );
            }
            context.captureState();
            return;
        }
        SearchResult children = searchChildren(
            context,
            task,
            taskRun,
            null
        );
        if (!children.settled() || !children.nexts().isEmpty() || !children.orchestrationCompletions().isEmpty()) {
            throw new IllegalStateException("Orchestration scope still has unfinished children: " + taskRun.id());
        }
        context.execution().succeedTaskRun(taskRun.id(), task.validateOutputs(Map.of()));
        context.captureState();
    }

    public void applyResult(ExecutorContext context, WorkerTaskResult result) {
        requireExecution(context, result.executionId());
        Execution execution = context.execution();
        TaskRun taskRun = execution.requireTaskRun(result.taskRunId());
        if (!taskRun.state().is(State.Type.RUNNING)) {
            throw new IllegalStateException("Worker result requires a RUNNING TaskRun");
        }
        Task task = context.flow().findTask(taskRun.taskId()).orElseThrow(() -> new WorkflowException("Task definition does not exist: " + taskRun.taskId()));
        switch (result.targetState()) {
            case SUCCESS -> execution.succeedTaskRun(taskRun.id(), task.validateOutputs(result.outputs()));
            case WARNING -> execution.warnTaskRun(taskRun.id(), task.validateOutputs(result.outputs()));
            case FAILED -> execution.failTaskRun(taskRun.id(), result.error());
            case KILLED -> {
                if (!execution.state().is(State.Type.KILLING)) {
                    execution.beginKilling();
                }
                execution.killUnfinishedTaskRuns();
                execution.finishKilling();
            }
            case CREATED, RUNNING, PAUSED, RESTARTED, SKIPPED, KILLING ->
                    throw new IllegalStateException("Worker cannot return " + result.targetState());
        }
        context.captureState();
    }

    public void resume(ExecutorContext context, String taskRunId, Map<String, ?> outputs) {
        context.execution().resumeTaskRun(taskRunId, outputs);
        context.captureState();
    }

    public void kill(ExecutorContext context) {
        context.execution().beginKilling();
        context.captureState();
    }

    private static void handleEnd(ExecutorContext context) {
        Execution execution = context.execution();
        if (!execution.state().is(State.Type.RUNNING)) {
            return;
        }
        SearchResult current = searchTopLevel(context, context.flow().tasks());
        if (!current.nexts().isEmpty() || !current.orchestrationCompletions().isEmpty()) {
            return;
        }
        if (current.settled()) {
            if (execution.effectiveTaskRuns().stream().anyMatch(taskRun ->
                taskRun.state().is(State.Type.WARNING)
            )) {
                execution.warn();
            } else {
                execution.succeed();
            }
            context.captureState();
            return;
        }
        if (canPauseExecution(context)) {
            execution.pauseTaskRuns(execution.activeTaskRuns().stream().map(TaskRun::id).toList());
            execution.pause();
            context.captureState();
            return;
        }
        if (execution.unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException("Execution has no runnable Task but the Flow is not settled: " + execution.id());
        }
    }

    private static boolean canPauseExecution(ExecutorContext context) {
        Execution execution = context.execution();
        if (execution.pausedTaskRuns().isEmpty()) {
            return false;
        }
        return execution.activeTaskRuns().stream().allMatch(taskRun -> {
            Task task = requireTask(context, taskRun);
            return taskRun.state().is(State.Type.RUNNING) && task instanceof OrchestrationTask orchestrationTask && orchestrationTask.holdsTaskRunUntilChildrenSettle();
        });
    }

    private static void requireStartableEmptyPlan(ExecutorContext context) {
        SearchResult current = searchTopLevel(context, context.flow().tasks());
        if (!current.nexts().isEmpty() || !current.orchestrationCompletions().isEmpty()) {
            throw new IllegalStateException("The staged executor plan is stale");
        }
        if (!current.settled() && context.execution().unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException("Execution has no runnable Task but the Flow is not settled: " + context.execution().id());
        }
    }

    private static void requireRuntimeCapabilities(ExecutorContext context, List<TaskRun> nexts) {
        for (TaskRun taskRun : nexts) {
            if (!taskRun.state().is(State.Type.CREATED)) {
                throw new IllegalStateException("Only a CREATED TaskRun can be dispatched: " + taskRun.id());
            }
            Task task = requireTask(context, taskRun);
            boolean runnable = task instanceof RunnableTask;
            boolean orchestration = task instanceof OrchestrationTask;
            if (runnable == orchestration) {
                throw new IllegalStateException("Task must implement exactly one runtime capability: " + task.getType());
            }
        }
    }

    private static Task requireTask(ExecutorContext context, TaskRun taskRun) {
        return context.flow().findTask(taskRun.taskId()).orElseThrow(() -> new IllegalStateException("TaskRun references a missing Task: " + taskRun.taskId()));
    }

    private static SearchResult searchTopLevel(ExecutorContext context, List<Task> tasks) {
        Map<String, Object> visibleOutputs = new LinkedHashMap<>();
        Optional<RewindScope> rewind = RewindScope.from(context, tasks);
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            Integer executionGenerationVersion = rewind.isPresent()
                ? rewind.orElseThrow().versionFor(index)
                : null;
            SearchResult result = searchTask(
                context,
                task,
                null,
                Map.copyOf(visibleOutputs),
                null,
                executionGenerationVersion,
                null
            );
            if (result.hasPlannedEffects() || !result.settled()) {
                return result;
            }
            appendCompletedOutput(
                context,
                task,
                null,
                null,
                executionGenerationVersion,
                visibleOutputs
            );
        }
        return SearchResult.settledResult();
    }

    private static SearchResult searchTask(
        ExecutorContext context,
        Task task,
        String parentTaskRunId,
        Map<String, ?> flowingContext,
        Integer iteration,
        Integer executionGenerationVersion,
        IterationScope iterationScope
    ) {
        Optional<TaskRun> taskRun = executionGenerationVersion == null
            ? context.execution().taskRunForOccurrence(
                task.id(),
                parentTaskRunId,
                iteration
            )
            : context.execution().taskRunForOccurrence(
                task.id(),
                parentTaskRunId,
                iteration,
                executionGenerationVersion
            );
        if (taskRun.isEmpty()) {
            return SearchResult.nexts(List.of(candidate(
                task,
                parentTaskRunId,
                flowingContext,
                iteration,
                executionGenerationVersion
            )));
        }

        TaskRun existing = taskRun.orElseThrow();
        return switch (existing.state().current()) {
            case CREATED -> SearchResult.nexts(List.of(existing));
            case RUNNING -> searchRunningTask(
                context,
                task,
                existing,
                iterationScope
            );
            case PAUSED -> SearchResult.unsettled();
            case SKIPPED -> SearchResult.settledResult();
            case SUCCESS, WARNING -> task instanceof Route
                || iteratesChildren(task)
                ? SearchResult.settledResult()
                : searchChildren(context, task, existing, iterationScope);
            case FAILED, KILLED -> SearchResult.unsettled();
            case RESTARTED, KILLING ->
                    throw new IllegalStateException("TaskRun has an Execution-only state: " + existing.state().current());
        };
    }

    private static SearchResult searchRunningTask(
        ExecutorContext context,
        Task task,
        TaskRun taskRun,
        IterationScope iterationScope
    ) {
        if (task instanceof OrchestrationTask orchestrationTask
            && orchestrationTask.iteratesChildren()) {
            return searchIterativeScope(
                context,
                task,
                orchestrationTask,
                taskRun,
                iterationScope
            );
        }
        if (task instanceof Pause pause) {
            if (hasPaused(taskRun)) {
                return SearchResult.orchestrationCompletion(taskRun.id());
            }
            SearchResult action = searchTask(
                context,
                pause.pause(),
                taskRun.id(),
                taskRun.inputs(),
                null,
                taskRun.executionGenerationVersion().isPresent()
                    ? taskRun.executionGenerationVersion().getAsInt()
                    : null,
                iterationScope
            );
            if (action.settled() && !action.hasPlannedEffects()) {
                return SearchResult.orchestrationCompletion(taskRun.id());
            }
            return action.asUnsettled();
        }
        if (!(task instanceof OrchestrationTask orchestrationTask) || !orchestrationTask.holdsTaskRunUntilChildrenSettle()) {
            return SearchResult.unsettled();
        }
        SearchResult children = searchChildren(
            context,
            task,
            taskRun,
            iterationScope
        );
        if (children.settled() && !children.hasPlannedEffects()) {
            return SearchResult.orchestrationCompletion(taskRun.id());
        }
        return children.asUnsettled();
    }

    private static SearchResult searchIterativeScope(
        ExecutorContext context,
        Task task,
        OrchestrationTask orchestrationTask,
        TaskRun loopRun,
        IterationScope parentScope
    ) {
        int iteration = loopRun.generation().current()
            .orElseThrow(() -> new IllegalStateException(
                "Loop scope has no current Generation: " + loopRun.id()
            ))
            .version();
        IterationScope scope = IterationScope.from(
            task,
            loopRun.id(),
            iteration,
            parentScope
        );
        SearchResult body = searchLoopIteration(
            context,
            task,
            loopRun,
            scope
        );
        if (body.hasPlannedEffects() || !body.settled()) {
            return body.asUnsettled();
        }

        OrchestrationTask.IterationDecision decision = decideAfterIteration(
            context,
            task,
            orchestrationTask,
            loopRun,
            iteration,
            iterationOutputs(context, task, scope)
        );
        if (decision != OrchestrationTask.IterationDecision.CONTINUE) {
            return SearchResult.orchestrationCompletion(loopRun.id());
        }
        int nextIteration = iteration + 1;
        if (nextIteration > orchestrationTask.maxIterations()) {
            throw new IllegalStateException(
                "Loop requested an iteration beyond its maximum: "
                    + loopRun.id()
            );
        }
        context.execution().advanceTaskRunGeneration(
            loopRun.id(),
            iterationReason(task)
        );
        IterationScope nextScope = IterationScope.from(
            task,
            loopRun.id(),
            nextIteration,
            parentScope
        );
        return searchLoopIteration(
            context,
            task,
            loopRun,
            nextScope
        ).asUnsettled();
    }

    private static SearchResult searchLoopIteration(
        ExecutorContext context,
        Task loop,
        TaskRun loopRun,
        IterationScope scope
    ) {
        Map<String, Object> flowingContext = new LinkedHashMap<>(childFlowingContext(
            loop,
            loopRun
        ));
        for (Task child : loop.definitionChildren()) {
            SearchResult result = searchTask(
                context,
                child,
                loopRun.id(),
                Map.copyOf(flowingContext),
                scope.iteration(),
                loopRun.executionGenerationVersion().isPresent()
                    ? loopRun.executionGenerationVersion().getAsInt()
                    : null,
                scope
            );
            if (result.hasPlannedEffects() || !result.settled()) {
                return result;
            }
            appendCompletedOutput(
                context,
                child,
                loopRun.id(),
                scope.iteration(),
                loopRun.executionGenerationVersion().isPresent()
                    ? loopRun.executionGenerationVersion().getAsInt()
                    : null,
                flowingContext
            );
        }
        return SearchResult.settledResult();
    }

    private static OrchestrationTask.IterationDecision decideAfterIteration(
        ExecutorContext context,
        Task task,
        OrchestrationTask orchestrationTask,
        TaskRun taskRun,
        int completedIterations,
        Map<String, Map<String, Object>> iterationOutputs
    ) {
        if (task instanceof LoopUntil loopUntil) {
            Map<String, Object> variables = RunVariables.builder()
                .flow(context.flow())
                .execution(context.execution())
                .task(task)
                .taskRun(taskRun)
                .build();
            return loopUntil.decideAfterIteration(
                completedIterations,
                ConditionContext.from(variables)
            );
        }
        return orchestrationTask.decideAfterIteration(
            completedIterations,
            iterationOutputs
        );
    }

    private static String iterationReason(Task task) {
        if (task instanceof Loop) {
            return "FIXED_COUNT_NOT_REACHED";
        }
        if (task instanceof LoopUntil) {
            return "CONDITION_NOT_SATISFIED";
        }
        throw new IllegalStateException(
            "Unsupported iterative Task type: " + task.getType()
        );
    }

    private static Map<String, Map<String, Object>> iterationOutputs(
        ExecutorContext context,
        Task loop,
        IterationScope scope
    ) {
        Set<String> bodyTaskIds = loop.allDescendants().stream()
            .map(Task::id)
            .collect(java.util.stream.Collectors.toSet());
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        for (TaskRun taskRun : context.execution().taskRuns()) {
            if (!bodyTaskIds.contains(taskRun.taskId())
                || !belongsToIteration(context, taskRun, scope)
                || (!taskRun.state().is(State.Type.SUCCESS)
                    && !taskRun.state().is(State.Type.WARNING))) {
                continue;
            }
            Task task = requireTask(context, taskRun);
            outputs.put(task.key(), taskRun.outputs());
        }
        return Map.copyOf(outputs);
    }

    private static boolean hasPaused(TaskRun taskRun) {
        return taskRun.state().history().stream().anyMatch(history -> history.state() == State.Type.PAUSED);
    }

    private static SearchResult searchChildren(
        ExecutorContext context,
        Task parent,
        TaskRun parentRun,
        IterationScope iterationScope
    ) {
        if (parent instanceof OrchestrationTask orchestrationTask && orchestrationTask.startsChildrenInParallel()) {
            return searchParallelChildren(
                context,
                parent,
                parentRun,
                iterationScope
            );
        }
        return searchSerialChildren(
            context,
            parent,
            parentRun,
            iterationScope
        );
    }

    private static SearchResult searchSerialChildren(
        ExecutorContext context,
        Task parent,
        TaskRun parentRun,
        IterationScope iterationScope
    ) {
        Map<String, Object> flowingContext = new LinkedHashMap<>(
            childFlowingContext(parent, parentRun)
        );
        for (Task child : parent.definitionChildren()) {
            SearchResult childResult = searchTask(
                context,
                child,
                parentRun.id(),
                Map.copyOf(flowingContext),
                null,
                parentRun.executionGenerationVersion().isPresent()
                    ? parentRun.executionGenerationVersion().getAsInt()
                    : null,
                iterationScope
            );
            if (childResult.hasPlannedEffects() || !childResult.settled()) {
                return childResult;
            }
            appendCompletedOutput(
                context,
                child,
                parentRun.id(),
                null,
                parentRun.executionGenerationVersion().isPresent()
                    ? parentRun.executionGenerationVersion().getAsInt()
                    : null,
                flowingContext
            );
        }
        return SearchResult.settledResult();
    }

    private static SearchResult searchParallelChildren(
        ExecutorContext context,
        Task parent,
        TaskRun parentRun,
        IterationScope iterationScope
    ) {
        boolean settled = true;
        List<TaskRun> nexts = new ArrayList<>();
        List<String> orchestrationCompletions = new ArrayList<>();
        Map<String, Object> flowingContext = childFlowingContext(parent, parentRun);
        for (Task child : parent.definitionChildren()) {
            SearchResult branch = searchTask(
                context,
                child,
                parentRun.id(),
                flowingContext,
                null,
                parentRun.executionGenerationVersion().isPresent()
                    ? parentRun.executionGenerationVersion().getAsInt()
                    : null,
                iterationScope
            );
            nexts.addAll(branch.nexts());
            orchestrationCompletions.addAll(branch.orchestrationCompletions());
            if (!branch.settled()) {
                settled = false;
            }
        }
        return new SearchResult(nexts, orchestrationCompletions, settled);
    }

    private static Map<String, Object> childFlowingContext(Task parent, TaskRun parentRun) {
        if (!(parent instanceof Branch)) {
            return parentRun.outputs();
        }
        Object incoming = parentRun.inputs().get("outputs");
        if (!(incoming instanceof Map<?, ?> incomingMap) || incomingMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        incomingMap.forEach((key, value) -> copied.put(String.valueOf(key), value));
        return Map.copyOf(copied);
    }

    private static boolean matchesRouteCondition(
        ExecutorContext context,
        Route route,
        TaskRun taskRun
    ) {
        Map<String, Object> variables = RunVariables.builder()
            .flow(context.flow())
            .execution(context.execution())
            .task(route)
            .taskRun(taskRun)
            .build();
        return route.matches(ConditionContext.from(variables));
    }

    private static void appendCompletedOutput(
        ExecutorContext context,
        Task task,
        String parentTaskRunId,
        Integer iteration,
        Integer executionGenerationVersion,
        Map<String, Object> target
    ) {
        Optional<TaskRun> completed = executionGenerationVersion == null
            ? context.execution().taskRunForOccurrence(
                task.id(),
                parentTaskRunId,
                iteration
            )
            : context.execution().taskRunForOccurrence(
                task.id(),
                parentTaskRunId,
                iteration,
                executionGenerationVersion
            );
        completed.filter(taskRun ->
            taskRun.state().is(State.Type.SUCCESS)
                || taskRun.state().is(State.Type.WARNING)
        ).ifPresent(taskRun -> target.put(task.key(), taskRun.outputs()));
    }

    private static boolean iteratesChildren(Task task) {
        return task instanceof OrchestrationTask orchestrationTask
            && orchestrationTask.iteratesChildren();
    }

    private static boolean belongsToIteration(
        ExecutorContext context,
        TaskRun taskRun,
        IterationScope scope
    ) {
        TaskRun cursor = taskRun;
        while (cursor.parentId().isPresent()) {
            String parentTaskRunId = cursor.parentId().orElseThrow();
            if (parentTaskRunId.equals(scope.loopRunId())) {
                return cursor.iteration().isPresent()
                    && cursor.iteration().getAsInt() == scope.iteration();
            }
            Optional<TaskRun> parent = context.execution()
                .findTaskRun(parentTaskRunId);
            if (parent.isEmpty()) {
                return false;
            }
            cursor = parent.orElseThrow();
        }
        return false;
    }

    private static TaskRun candidate(
        Task task,
        String parentTaskRunId,
        Map<String, ?> parentOutputs,
        Integer iteration,
        Integer executionGenerationVersion
    ) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (parentOutputs != null && !parentOutputs.isEmpty()) {
            inputs.put("outputs", Map.copyOf(parentOutputs));
        }
        if (iteration != null) {
            inputs.put("loop", Map.of("iteration", iteration));
        }
        return TaskRun.create(
            task.id(),
            parentTaskRunId,
            Map.copyOf(inputs),
            iteration,
            executionGenerationVersion
        );
    }

    private static void requireExecution(ExecutorContext context, String executionId) {
        if (!context.execution().identifiedBy(executionId)) {
            throw new IllegalArgumentException("Worker message belongs to another Execution");
        }
    }

    private record RewindScope(
        int version,
        int targetIndex,
        int sourceIndex
    ) {

        private static Optional<RewindScope> from(
            ExecutorContext context,
            List<Task> topLevelTasks
        ) {
            Optional<Generation.Current> current = context.execution()
                .generation()
                .current();
            if (current.isEmpty()) {
                return Optional.empty();
            }
            Generation.Current active = current.orElseThrow();
            TaskRun source = context.execution().requireTaskRun(
                active.sourceTaskRunId().orElseThrow(() ->
                    new IllegalStateException(
                        "Execution Generation current requires a source"
                    )
                )
            );
            TaskRun target = context.execution().requireTaskRun(
                active.targetTaskRunId().orElseThrow(() ->
                    new IllegalStateException(
                        "Execution Generation current requires a target"
                    )
                )
            );
            int targetIndex = definitionIndex(topLevelTasks, target);
            int sourceIndex = definitionIndex(topLevelTasks, source);
            if (targetIndex >= sourceIndex) {
                throw new IllegalStateException(
                    "Rewind target must precede its source in the Flow"
                );
            }
            return Optional.of(new RewindScope(
                active.version(),
                targetIndex,
                sourceIndex
            ));
        }

        private Integer versionFor(int taskIndex) {
            return taskIndex >= targetIndex && taskIndex <= sourceIndex
                ? version
                : null;
        }

        private static int definitionIndex(
            List<Task> topLevelTasks,
            TaskRun taskRun
        ) {
            if (taskRun.parentId().isPresent()) {
                throw new WorkflowException(
                    "Rewind currently supports top-level serial TaskRuns only: "
                        + taskRun.id()
                );
            }
            for (int index = 0; index < topLevelTasks.size(); index++) {
                if (topLevelTasks.get(index).identifiedBy(taskRun.taskId())) {
                    return index;
                }
            }
            throw new WorkflowException(
                "Rewind TaskRun is not a top-level Flow Task: " + taskRun.id()
            );
        }
    }

    private record IterationScope(
        Task loop,
        String loopRunId,
        int iteration,
        IterationScope parent
    ) {

        private static IterationScope from(
            Task loop,
            String loopRunId,
            int iteration,
            IterationScope parent
        ) {
            return new IterationScope(loop, loopRunId, iteration, parent);
        }
    }

    private static final class SearchResult {

        private final List<TaskRun> nexts;
        private final List<String> orchestrationCompletions;
        private final boolean settled;

        private SearchResult(List<TaskRun> nexts, List<String> orchestrationCompletions, boolean settled) {
            this.nexts = List.copyOf(nexts);
            this.orchestrationCompletions = List.copyOf(orchestrationCompletions);
            this.settled = settled;
        }

        private static SearchResult nexts(List<TaskRun> nexts) {
            return new SearchResult(nexts, List.of(), false);
        }

        private static SearchResult orchestrationCompletion(String taskRunId) {
            return new SearchResult(List.of(), List.of(taskRunId), false);
        }

        private static SearchResult unsettled() {
            return new SearchResult(List.of(), List.of(), false);
        }

        private static SearchResult settledResult() {
            return new SearchResult(List.of(), List.of(), true);
        }

        private List<TaskRun> nexts() {
            return nexts;
        }

        private List<String> orchestrationCompletions() {
            return orchestrationCompletions;
        }

        private boolean settled() {
            return settled;
        }

        private boolean hasPlannedEffects() {
            return !nexts.isEmpty() || !orchestrationCompletions.isEmpty();
        }

        private SearchResult asUnsettled() {
            return settled ? new SearchResult(nexts, orchestrationCompletions, false) : this;
        }
    }

}
