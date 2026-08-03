package org.cses.flow.executor;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.BranchTask;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Internal state-machine implementation for one {@link ExecutorContext}.
 *
 * <p>{@link #advance(ExecutorContext)} owns the plan-and-apply order. Planning
 * only stages the next batch; applying attaches newly planned TaskRuns to the
 * Execution and reports whether the aggregate changed.</p>
 */
@Singleton
public final class ExecutorService {

    boolean advance(ExecutorContext context) {
        handleNext(context);
        return onNexts(context);
    }

    /**
     * Reconstructs and stages the next runnable TaskRun batch without
     * changing the Execution aggregate.
     */
    void handleNext(ExecutorContext context) {
        Execution execution = context.execution();
        if (execution.state().isTerminal()
            || execution.state().isWaiting()) {
            context.stageNexts(List.of());
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
    }

    /**
     * Consumes the staged next batch exactly once and applies its effects.
     */
    boolean onNexts(ExecutorContext context) {
        List<TaskRun> nexts = context.takeNexts();
        Execution execution = context.execution();
        if (execution.state().isTerminal()
            || execution.state().isWaiting()) {
            if (!nexts.isEmpty()) {
                throw new IllegalStateException(
                    "A terminal or waiting Execution cannot accept nexts"
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
            for (TaskRun branchTaskRun : workPlan.branchTaskRuns()) {
                context.stageBranchTaskRun(branchTaskRun);
            }
            return updated;
        }

        return settle(context) || updated;
    }

    void dispatch(
        ExecutorContext context,
        WorkerTask workerTask
    ) {
        requireExecution(context, workerTask.executionId());
        context.execution().startTaskRun(workerTask.taskRunId());
        context.captureState();
    }

    void dispatchBranch(
        ExecutorContext context,
        TaskRun plannedTaskRun
    ) {
        TaskRun taskRun = context.execution().requireTaskRun(
            plannedTaskRun.id()
        );
        if (!taskRun.state().is(State.Type.CREATED)) {
            throw new IllegalStateException(
                "Only a CREATED Branch TaskRun can be handled: "
                    + taskRun.id()
            );
        }
        Task task = context.flow().findTask(taskRun.taskId())
            .orElseThrow(() -> new IllegalStateException(
                "TaskRun references a missing Task: " + taskRun.taskId()
            ));
        if (!(task instanceof BranchTask branchTask)
            || task instanceof RunnableTask) {
            throw new IllegalStateException(
                "Branch dispatch requires only the BranchTask capability: "
                    + task.type()
            );
        }

        Execution execution = context.execution();
        execution.startTaskRun(taskRun.id());
        if (branchTask.waitsForResume()) {
            execution.waitTaskRun(taskRun.id());
        } else {
            execution.completeTaskRun(
                taskRun.id(),
                task.validateOutputs(Map.of())
            );
        }
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
            case CREATED, RUNNING, WAITING -> throw new IllegalStateException(
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
        if (!current.nexts().isEmpty()) {
            throw new IllegalStateException(
                "The staged next-task plan is stale"
            );
        }
        if (current.settled()) {
            execution.complete();
            context.captureState();
            return true;
        }
        if (execution.activeTaskRuns().isEmpty()
            && !execution.waitingTaskRuns().isEmpty()) {
            execution.enterWaiting();
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
        if (!current.nexts().isEmpty()) {
            throw new IllegalStateException(
                "The staged next-task plan is stale"
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
        List<TaskRun> branchTaskRuns = new ArrayList<>(nexts.size());
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
            boolean branch = task instanceof BranchTask;
            if (runnable == branch) {
                throw new IllegalStateException(
                    "Task must implement exactly one runtime capability: "
                        + task.type()
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
                branchTaskRuns.add(taskRun);
            }
        }
        return new WorkPlan(workerTasks, branchTaskRuns);
    }

    private static SearchResult searchTopLevel(
        ExecutorContext context,
        List<Task> tasks
    ) {
        Execution execution = context.execution();
        for (Task task : tasks) {
            Optional<TaskRun> taskRun =
                execution.latestTaskRunForTask(task.id());
            if (taskRun.isEmpty()) {
                if (dependenciesCompleted(context, task)) {
                    return SearchResult.nexts(List.of(candidate(
                        context,
                        task,
                        null,
                        Map.of()
                    )));
                }
                return SearchResult.unsettled();
            }
            TaskRun existing = taskRun.orElseThrow();
            if (existing.state().is(State.Type.CREATED)) {
                return SearchResult.nexts(List.of(existing));
            }
            if (!existing.state().is(State.Type.COMPLETED)) {
                return SearchResult.unsettled();
            }
            SearchResult children = searchChildren(
                context,
                task,
                existing
            );
            if (!children.settled() || !children.nexts().isEmpty()) {
                return children;
            }
        }
        return SearchResult.settledResult();
    }

    private static SearchResult searchChildren(
        ExecutorContext context,
        Task parent,
        TaskRun parentRun
    ) {
        if (parent instanceof BranchTask branchTask
            && branchTask.startsChildrenInParallel()) {
            return searchParallelChildren(context, parent, parentRun);
        }
        return searchSerialChildren(context, parent, parentRun);
    }

    private static SearchResult searchSerialChildren(
        ExecutorContext context,
        Task parent,
        TaskRun parentRun
    ) {
        for (Task child : parent.tasks()) {
            if (!child.matchesRoute(parentRun.outputs())) {
                continue;
            }
            Optional<TaskRun> childRun = context.execution()
                .latestTaskRunForTask(child.id());
            if (childRun.isEmpty()) {
                if (!dependenciesCompleted(context, child)) {
                    return SearchResult.unsettled();
                }
                return SearchResult.nexts(List.of(candidate(
                    context,
                    child,
                    parentRun.id(),
                    parentRun.outputs()
                )));
            }
            TaskRun existing = childRun.orElseThrow();
            if (existing.state().is(State.Type.CREATED)) {
                return SearchResult.nexts(List.of(existing));
            }
            if (!existing.state().is(State.Type.COMPLETED)) {
                return SearchResult.unsettled();
            }
            SearchResult descendants = searchChildren(
                context,
                child,
                existing
            );
            if (!descendants.settled()
                || !descendants.nexts().isEmpty()) {
                return descendants;
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
        for (Task child : parent.tasks()) {
            if (!child.matchesRoute(parentRun.outputs())) {
                continue;
            }
            Optional<TaskRun> childRun = context.execution()
                .latestTaskRunForTask(child.id());
            if (childRun.isEmpty()) {
                settled = false;
                if (dependenciesCompleted(context, child)) {
                    nexts.add(candidate(
                        context,
                        child,
                        parentRun.id(),
                        parentRun.outputs()
                    ));
                }
                continue;
            }
            TaskRun existing = childRun.orElseThrow();
            if (existing.state().is(State.Type.CREATED)) {
                settled = false;
                nexts.add(existing);
                continue;
            }
            if (!existing.state().is(State.Type.COMPLETED)) {
                settled = false;
                continue;
            }
            SearchResult descendants = searchChildren(
                context,
                child,
                existing
            );
            nexts.addAll(descendants.nexts());
            if (!descendants.settled()) {
                settled = false;
            }
        }
        return new SearchResult(nexts, settled);
    }

    private static boolean dependenciesCompleted(
        ExecutorContext context,
        Task task
    ) {
        for (String dependencyKey : task.dependOn()) {
            Task dependency = findTaskByKey(
                context.flow().allTasks(),
                dependencyKey
            ).orElseThrow(() -> new IllegalStateException(
                "Validated Task dependency is missing: " + dependencyKey
            ));
            Optional<TaskRun> dependencyRun = context.execution()
                .latestTaskRunForTask(dependency.id());
            if (dependencyRun.isEmpty()
                || !dependencyRun.orElseThrow().state()
                    .is(State.Type.COMPLETED)) {
                return false;
            }
        }
        return true;
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
        private final boolean settled;

        private SearchResult(List<TaskRun> nexts, boolean settled) {
            this.nexts = List.copyOf(nexts);
            this.settled = settled;
        }

        private static SearchResult nexts(List<TaskRun> nexts) {
            return new SearchResult(nexts, false);
        }

        private static SearchResult unsettled() {
            return new SearchResult(List.of(), false);
        }

        private static SearchResult settledResult() {
            return new SearchResult(List.of(), true);
        }

        private List<TaskRun> nexts() {
            return nexts;
        }

        private boolean settled() {
            return settled;
        }
    }

    private static final class WorkPlan {

        private final List<WorkerTask> workerTasks;
        private final List<TaskRun> branchTaskRuns;

        private WorkPlan(
            List<WorkerTask> workerTasks,
            List<TaskRun> branchTaskRuns
        ) {
            this.workerTasks = List.copyOf(workerTasks);
            this.branchTaskRuns = List.copyOf(branchTaskRuns);
        }

        private List<WorkerTask> workerTasks() {
            return workerTasks;
        }

        private List<TaskRun> branchTaskRuns() {
            return branchTaskRuns;
        }
    }
}
