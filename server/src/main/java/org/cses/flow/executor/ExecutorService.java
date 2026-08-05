package org.cses.flow.executor;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Internal state-machine implementation for one {@link ExecutorContext}.
 *
 * <p>{@link #handle(ExecutorContext)} owns the plan-and-apply loop. Planning
 * only stages the next batch; applying attaches newly planned TaskRuns,
 * handles OrchestrationTasks inside the Executor, and stops only at a Worker or
 * stable-state boundary.</p>
 */
@Singleton
public final class ExecutorService {

    boolean handle(ExecutorContext context) {
        if (!context.workerTasks().isEmpty()) {
            throw new IllegalStateException(
                "Pending WorkerTasks must be consumed before handling nexts"
            );
        }

        boolean executionChanged = false;
        while (true) {
            handleNext(context);
            boolean advanced = onNexts(context);
            executionChanged = executionChanged || advanced;
            if (!context.workerTasks().isEmpty()
                || context.execution().state().isTerminal()
                || !advanced) {
                return executionChanged;
            }
        }
    }

    /**
     * Reconstructs and stages the next TaskRun batch without
     * changing the Execution aggregate.
     */
    void handleNext(ExecutorContext context) {
        Execution execution = context.execution();
        if (execution.state().isTerminal()) {
            context.stageNexts(List.of());
            context.stageOrchestrationCompletions(List.of());
            return;
        }
        if (!execution.state().is(State.Type.CREATED)
            && !execution.state().is(State.Type.RUNNING)) {
            throw new WorkflowException(
                "Execution cannot plan work from "
                    + execution.state().current()
                    + ": "
                    + execution.id()
            );
        }

        SearchResult result = searchTopLevel(
            context,
            context.flow().tasks()
        );
        context.stageNexts(result.nexts());
        context.stageOrchestrationCompletions(
            result.orchestrationCompletions()
        );
    }

    /**
     * Consumes the staged next batch exactly once and applies its effects.
     */
    boolean onNexts(ExecutorContext context) {
        List<TaskRun> nexts = context.takeNexts();
        List<String> orchestrationCompletions =
            context.takeOrchestrationCompletions();
        Execution execution = context.execution();
        if (execution.state().isTerminal()) {
            if (!nexts.isEmpty() || !orchestrationCompletions.isEmpty()) {
                throw new IllegalStateException(
                    "A terminal Execution cannot accept a work plan"
                );
            }
            return false;
        }

        WorkPlan workPlan = workPlan(
            context,
            nexts
        );
        List<TaskRun> unattached = nexts.stream()
            .filter(taskRun ->
                execution.findTaskRun(taskRun.id()).isEmpty()
            )
            .toList();

        boolean updated = false;
        if (execution.state().is(State.Type.CREATED)
            && !unattached.isEmpty()) {
            if (unattached.size() != nexts.size()) {
                throw new IllegalStateException(
                    "A CREATED Execution cannot contain TaskRuns"
                );
            }
            execution.startWithTaskRuns(unattached);
            updated = true;
        } else if (execution.state().is(State.Type.CREATED)) {
            requireStartableEmptyPlan(context);
            execution.start();
            updated = true;
        } else if (!unattached.isEmpty()) {
            execution.addTaskRuns(unattached);
            updated = true;
        }
        if (updated) {
            context.captureState();
        }

        if (!nexts.isEmpty()) {
            for (WorkerTask workerTask : workPlan.workerTasks()) {
                context.stageWorkerTask(workerTask);
            }
            for (TaskRun orchestrationTaskRun
                : workPlan.orchestrationTaskRuns()) {
                if (handleOrchestration(context, orchestrationTaskRun)) {
                    context.stagePausedTaskRun(orchestrationTaskRun);
                }
                updated = true;
            }
        }

        for (String taskRunId : orchestrationCompletions) {
            completeOrchestrationScope(context, taskRunId);
            updated = true;
        }

        if (!nexts.isEmpty()) {
            return updated;
        }

        if (updated) {
            return true;
        }
        return settle(context);
    }

    void dispatch(
        ExecutorContext context,
        WorkerTask workerTask
    ) {
        requireExecution(context, workerTask.executionId());
        context.execution().startTaskRun(workerTask.taskRunId());
        context.captureState();
    }

    private static boolean handleOrchestration(
        ExecutorContext context,
        TaskRun plannedTaskRun
    ) {
        TaskRun taskRun = context.execution().requireTaskRun(
            plannedTaskRun.id()
        );
        if (!taskRun.state().is(State.Type.CREATED)) {
            throw new IllegalStateException(
                "Only a CREATED Orchestration TaskRun can be handled: "
                    + taskRun.id()
            );
        }
        Task task = context.flow().findTask(taskRun.taskId())
            .orElseThrow(() -> new IllegalStateException(
                "TaskRun references a missing Task: " + taskRun.taskId()
            ));
        if (!(task instanceof OrchestrationTask orchestrationTask)
            || task instanceof RunnableTask) {
            throw new IllegalStateException(
                "Orchestration handling requires only the "
                    + "OrchestrationTask capability: "
                    + task.getType()
            );
        }

        Execution execution = context.execution();
        execution.startTaskRun(taskRun.id());
        boolean pausesTaskRun = orchestrationTask.pausesTaskRun();
        boolean holdsScope =
            orchestrationTask.holdsTaskRunUntilChildrenSettle();
        if (pausesTaskRun && holdsScope) {
            throw new IllegalStateException(
                "An OrchestrationTask cannot pause and hold a child scope: "
                    + task.getType()
            );
        }
        if (!pausesTaskRun && !holdsScope) {
            execution.completeTaskRun(
                taskRun.id(),
                task.validateOutputs(Map.of())
            );
        }
        context.captureState();
        return false;
    }

    private static void completeOrchestrationScope(
        ExecutorContext context,
        String taskRunId
    ) {
        TaskRun taskRun = context.execution().requireTaskRun(taskRunId);
        if (!taskRun.state().is(State.Type.RUNNING)) {
            throw new IllegalStateException(
                "Only a RUNNING orchestration scope can complete: "
                    + taskRun.id()
            );
        }
        Task task = context.flow().findTask(taskRun.taskId())
            .orElseThrow(() -> new IllegalStateException(
                "TaskRun references a missing Task: " + taskRun.taskId()
            ));
        if (!(task instanceof OrchestrationTask orchestrationTask)
            || (!orchestrationTask.holdsTaskRunUntilChildrenSettle()
                && !orchestrationTask.pausesTaskRun())) {
            throw new IllegalStateException(
                "TaskRun is not an orchestration scope: " + taskRun.id()
            );
        }
        if (orchestrationTask.pausesTaskRun()) {
            if (hasPaused(taskRun)) {
                context.execution().completeTaskRun(
                    taskRun.id(),
                    taskRun.outputs()
                );
            } else {
                context.execution().pauseTaskRun(taskRun.id());
                context.stagePausedTaskRun(taskRun);
            }
            context.captureState();
            return;
        }
        SearchResult children = searchChildren(context, task, taskRun);
        if (!children.settled()
            || !children.nexts().isEmpty()
            || !children.orchestrationCompletions().isEmpty()) {
            throw new IllegalStateException(
                "Orchestration scope still has unfinished children: "
                    + taskRun.id()
            );
        }
        context.execution().completeTaskRun(
            taskRun.id(),
            task.validateOutputs(Map.of())
        );
        context.captureState();
    }

    void applyResult(
        ExecutorContext context,
        WorkerTaskResult result
    ) {
        requireExecution(context, result.executionId());
        Execution execution = context.execution();
        TaskRun taskRun = execution.requireTaskRun(result.taskRunId());
        if (!taskRun.state().is(State.Type.RUNNING)) {
            throw new IllegalStateException(
                "Worker result requires a RUNNING TaskRun"
            );
        }
        Task task = context.flow().findTask(taskRun.taskId())
            .orElseThrow(() -> new WorkflowException(
                "Task definition does not exist: " + taskRun.taskId()
            ));
        switch (result.targetState()) {
            case COMPLETED -> execution.completeTaskRun(
                taskRun.id(),
                task.validateOutputs(result.outputs())
            );
            case TERMINATED -> execution.failTaskRun(
                taskRun.id(),
                result.error()
            );
            case CREATED, RUNNING, PAUSED, WARNING, CANCELLED,
                FAILED -> throw new IllegalStateException(
                "Worker cannot return " + result.targetState()
            );
        }
        context.captureState();
    }

    void resume(
        ExecutorContext context,
        String taskRunId,
        Map<String, ?> outputs
    ) {
        context.execution().resumeTaskRun(taskRunId, outputs);
        context.captureState();
    }

    void cancel(ExecutorContext context) {
        context.execution().cancel();
        context.captureState();
    }

    private static boolean settle(ExecutorContext context) {
        Execution execution = context.execution();
        SearchResult current = searchTopLevel(
            context,
            context.flow().tasks()
        );
        if (!current.nexts().isEmpty()
            || !current.orchestrationCompletions().isEmpty()) {
            throw new IllegalStateException(
                "The staged executor plan is stale"
            );
        }
        if (current.settled()) {
            execution.complete();
            context.captureState();
            return true;
        }
        if (execution.unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException(
                "Execution has no runnable Task but the Flow is not settled: "
                + execution.id()
            );
        }
        return false;
    }

    private static void requireStartableEmptyPlan(
        ExecutorContext context
    ) {
        SearchResult current = searchTopLevel(
            context,
            context.flow().tasks()
        );
        if (!current.nexts().isEmpty()
            || !current.orchestrationCompletions().isEmpty()) {
            throw new IllegalStateException(
                "The staged executor plan is stale"
            );
        }
        if (!current.settled()
            && context.execution().unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException(
                "Execution has no runnable Task but the Flow is not settled: "
                    + context.execution().id()
            );
        }
    }

    private static WorkPlan workPlan(
        ExecutorContext context,
        List<TaskRun> nexts
    ) {
        List<WorkerTask> workerTasks = new ArrayList<>(nexts.size());
        List<TaskRun> orchestrationTaskRuns = new ArrayList<>(nexts.size());
        for (TaskRun taskRun : nexts) {
            if (!taskRun.state().is(State.Type.CREATED)) {
                throw new IllegalStateException(
                    "Only a CREATED TaskRun can be dispatched: "
                        + taskRun.id()
                );
            }
            Task task = context.flow()
                .findTask(taskRun.taskId())
                .orElseThrow(() -> new IllegalStateException(
                    "TaskRun references a missing Task: " + taskRun.taskId()
                ));
            boolean runnable = task instanceof RunnableTask;
            boolean orchestration = task instanceof OrchestrationTask;
            if (runnable == orchestration) {
                throw new IllegalStateException(
                    "Task must implement exactly one runtime capability: "
                        + task.getType()
                );
            }
            if (runnable) {
                workerTasks.add(new WorkerTask(
                    context.execution().id(),
                    taskRun.id(),
                    task,
                    taskRun.inputs()
                ));
            } else {
                orchestrationTaskRuns.add(taskRun);
            }
        }
        return new WorkPlan(workerTasks, orchestrationTaskRuns);
    }

    private static SearchResult searchTopLevel(
        ExecutorContext context,
        List<Task> tasks
    ) {
        for (Task task : tasks) {
            SearchResult result = searchTask(
                context,
                task,
                null,
                Map.of()
            );
            if (result.hasPlannedEffects() || !result.settled()) {
                return result;
            }
        }
        return SearchResult.settledResult();
    }

    private static SearchResult searchTask(
        ExecutorContext context,
        Task task,
        String parentTaskRunId,
        Map<String, ?> flowingContext
    ) {
        Optional<TaskRun> taskRun = context.execution()
            .latestTaskRunForTask(task.id());
        if (taskRun.isEmpty()) {
            DependencyState dependencies = dependencyState(context, task);
            return switch (dependencies) {
                case SATISFIED -> SearchResult.nexts(List.of(candidate(
                    context,
                    task,
                    parentTaskRunId,
                    flowingContext
                )));
                case PENDING -> SearchResult.unsettled();
                case UNSELECTED -> SearchResult.settledResult();
            };
        }

        TaskRun existing = taskRun.orElseThrow();
        return switch (existing.state().current()) {
            case CREATED -> SearchResult.nexts(List.of(existing));
            case RUNNING -> searchRunningTask(context, task, existing);
            case PAUSED -> SearchResult.unsettled();
            case COMPLETED -> searchChildren(context, task, existing);
            case WARNING, CANCELLED, FAILED, TERMINATED ->
                SearchResult.unsettled();
        };
    }

    private static SearchResult searchRunningTask(
        ExecutorContext context,
        Task task,
        TaskRun taskRun
    ) {
        if (task instanceof Pause pause) {
            if (hasPaused(taskRun)) {
                return SearchResult.orchestrationCompletion(taskRun.id());
            }
            SearchResult action = searchTask(
                context,
                pause.pause(),
                taskRun.id(),
                taskRun.inputs()
            );
            if (action.settled() && !action.hasPlannedEffects()) {
                return SearchResult.orchestrationCompletion(taskRun.id());
            }
            return action.asUnsettled();
        }
        if (!(task instanceof OrchestrationTask orchestrationTask)
            || !orchestrationTask.holdsTaskRunUntilChildrenSettle()) {
            return SearchResult.unsettled();
        }
        SearchResult children = searchChildren(context, task, taskRun);
        if (children.settled() && !children.hasPlannedEffects()) {
            return SearchResult.orchestrationCompletion(taskRun.id());
        }
        return children.asUnsettled();
    }

    private static boolean hasPaused(TaskRun taskRun) {
        return taskRun.state().history().stream()
            .anyMatch(history -> history.state() == State.Type.PAUSED);
    }

    private static SearchResult searchChildren(
        ExecutorContext context,
        Task parent,
        TaskRun parentRun
    ) {
        if (parent instanceof OrchestrationTask orchestrationTask
            && orchestrationTask.startsChildrenInParallel()) {
            return searchParallelChildren(context, parent, parentRun);
        }
        return searchSerialChildren(context, parent, parentRun);
    }

    private static SearchResult searchSerialChildren(
        ExecutorContext context,
        Task parent,
        TaskRun parentRun
    ) {
        Map<String, Object> flowingContext = childFlowingContext(
            parent,
            parentRun
        );
        for (Task child : parent.tasks()) {
            if (!child.matchesRoute(flowingContext)) {
                continue;
            }
            SearchResult childResult = searchTask(
                context,
                child,
                parentRun.id(),
                flowingContext
            );
            if (childResult.hasPlannedEffects()
                || !childResult.settled()) {
                return childResult;
            }
        }
        return SearchResult.settledResult();
    }

    private static SearchResult searchParallelChildren(
        ExecutorContext context,
        Task parent,
        TaskRun parentRun
    ) {
        boolean settled = true;
        List<TaskRun> nexts = new ArrayList<>();
        List<String> orchestrationCompletions = new ArrayList<>();
        Map<String, Object> flowingContext = childFlowingContext(
            parent,
            parentRun
        );
        for (Task child : parent.tasks()) {
            if (!child.matchesRoute(flowingContext)) {
                continue;
            }
            SearchResult branch = searchTask(
                context,
                child,
                parentRun.id(),
                flowingContext
            );
            nexts.addAll(branch.nexts());
            orchestrationCompletions.addAll(
                branch.orchestrationCompletions()
            );
            if (!branch.settled()) {
                settled = false;
            }
        }
        return new SearchResult(
            nexts,
            orchestrationCompletions,
            settled
        );
    }

    private static DependencyState dependencyState(
        ExecutorContext context,
        Task task
    ) {
        return dependencyState(context, task, new LinkedHashSet<>());
    }

    private static DependencyState dependencyState(
        ExecutorContext context,
        Task task,
        Set<String> path
    ) {
        if (!path.add(task.key())) {
            return DependencyState.PENDING;
        }
        boolean pending = false;
        for (String dependencyKey : task.dependOn()) {
            Task dependency = findTaskByKey(
                context.flow().allTasks(),
                dependencyKey
            ).orElseThrow(() -> new IllegalStateException(
                "Validated Task dependency is missing: " + dependencyKey
            ));
            DependencyState current = dependencyFact(
                context,
                dependency,
                new LinkedHashSet<>(path)
            );
            if (current == DependencyState.UNSELECTED) {
                return DependencyState.UNSELECTED;
            }
            if (current == DependencyState.PENDING) {
                pending = true;
            }
        }
        return pending
            ? DependencyState.PENDING
            : DependencyState.SATISFIED;
    }

    private static DependencyState dependencyFact(
        ExecutorContext context,
        Task task,
        Set<String> path
    ) {
        Optional<TaskRun> existing = context.execution()
            .latestTaskRunForTask(task.id());
        if (existing.isPresent()) {
            return existing.orElseThrow().state().is(State.Type.COMPLETED)
                ? DependencyState.SATISFIED
                : DependencyState.PENDING;
        }

        Optional<Task> parent = findParentTask(
            context.flow().tasks(),
            task.id()
        );
        if (parent.isEmpty()) {
            return DependencyState.PENDING;
        }
        Task parentTask = parent.orElseThrow();
        Optional<TaskRun> parentRun = context.execution()
            .latestTaskRunForTask(parentTask.id());
        if (parentRun.isEmpty()) {
            DependencyState parentFact = dependencyFact(
                context,
                parentTask,
                new LinkedHashSet<>(path)
            );
            return parentFact == DependencyState.UNSELECTED
                ? DependencyState.UNSELECTED
                : DependencyState.PENDING;
        }

        TaskRun actualParentRun = parentRun.orElseThrow();
        if (!routeCanBeEvaluated(parentTask, actualParentRun)) {
            return DependencyState.PENDING;
        }
        if (!task.matchesRoute(childFlowingContext(
            parentTask,
            actualParentRun
        ))) {
            return DependencyState.UNSELECTED;
        }

        DependencyState ownDependencies = dependencyState(
            context,
            task,
            path
        );
        return ownDependencies == DependencyState.UNSELECTED
            ? DependencyState.UNSELECTED
            : DependencyState.PENDING;
    }

    private static boolean routeCanBeEvaluated(
        Task parent,
        TaskRun parentRun
    ) {
        if (parentRun.state().is(State.Type.COMPLETED)) {
            return true;
        }
        return parentRun.state().is(State.Type.RUNNING)
            && parent instanceof OrchestrationTask orchestrationTask
            && orchestrationTask.startsChildrenInParallel();
    }

    private static Map<String, Object> childFlowingContext(
        Task parent,
        TaskRun parentRun
    ) {
        if (!(parent instanceof OrchestrationTask orchestrationTask)
            || !orchestrationTask.startsChildrenInParallel()) {
            return parentRun.outputs();
        }
        Object incoming = parentRun.inputs().get("outputs");
        if (!(incoming instanceof Map<?, ?> incomingMap)
            || incomingMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        incomingMap.forEach((key, value) -> copied.put(
            String.valueOf(key),
            value
        ));
        return Map.copyOf(copied);
    }

    private static Optional<Task> findParentTask(
        List<Task> tasks,
        String childTaskId
    ) {
        for (Task task : tasks) {
            if (task.tasks().stream().anyMatch(child ->
                child.id().equals(childTaskId)
            )) {
                return Optional.of(task);
            }
            Optional<Task> nested = findParentTask(
                task.tasks(),
                childTaskId
            );
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    private static TaskRun candidate(
        ExecutorContext context,
        Task task,
        String parentTaskRunId,
        Map<String, ?> parentOutputs
    ) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (parentOutputs != null && !parentOutputs.isEmpty()) {
            inputs.put("outputs", Map.copyOf(parentOutputs));
        }
        if (!task.dependOn().isEmpty()) {
            Map<String, Object> dependencyOutputs = new LinkedHashMap<>();
            for (String dependencyKey : task.dependOn()) {
                Task dependency = findTaskByKey(
                    context.flow().allTasks(),
                    dependencyKey
                ).orElseThrow();
                TaskRun dependencyRun = context.execution()
                    .latestTaskRunForTask(dependency.id())
                    .orElseThrow();
                dependencyOutputs.put(
                    dependencyKey,
                    dependencyRun.outputs()
                );
            }
            inputs.put(
                "dependOnOutputs",
                Map.copyOf(dependencyOutputs)
            );
        }
        return TaskRun.create(
            task.id(),
            parentTaskRunId,
            Map.copyOf(inputs)
        );
    }

    private static Optional<Task> findTaskByKey(
        List<Task> tasks,
        String taskKey
    ) {
        return tasks.stream()
            .filter(task -> task.key().equals(taskKey))
            .findFirst();
    }

    private static void requireExecution(
        ExecutorContext context,
        String executionId
    ) {
        if (!context.execution().id().equals(executionId)) {
            throw new IllegalArgumentException(
                "Worker message belongs to another Execution"
            );
        }
    }

    private static final class SearchResult {

        private final List<TaskRun> nexts;
        private final List<String> orchestrationCompletions;
        private final boolean settled;

        private SearchResult(
            List<TaskRun> nexts,
            List<String> orchestrationCompletions,
            boolean settled
        ) {
            this.nexts = List.copyOf(nexts);
            this.orchestrationCompletions = List.copyOf(
                orchestrationCompletions
            );
            this.settled = settled;
        }

        private static SearchResult nexts(List<TaskRun> nexts) {
            return new SearchResult(nexts, List.of(), false);
        }

        private static SearchResult orchestrationCompletion(
            String taskRunId
        ) {
            return new SearchResult(
                List.of(),
                List.of(taskRunId),
                false
            );
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
            return settled
                ? new SearchResult(nexts, orchestrationCompletions, false)
                : this;
        }
    }

    private static final class WorkPlan {

        private final List<WorkerTask> workerTasks;
        private final List<TaskRun> orchestrationTaskRuns;

        private WorkPlan(
            List<WorkerTask> workerTasks,
            List<TaskRun> orchestrationTaskRuns
        ) {
            this.workerTasks = List.copyOf(workerTasks);
            this.orchestrationTaskRuns = List.copyOf(
                orchestrationTaskRuns
            );
        }

        private List<WorkerTask> workerTasks() {
            return workerTasks;
        }

        private List<TaskRun> orchestrationTaskRuns() {
            return orchestrationTaskRuns;
        }
    }

    private enum DependencyState {
        SATISFIED,
        PENDING,
        UNSELECTED
    }
}
