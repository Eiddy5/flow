package org.cses.flow.executor;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.ExecutionStatus;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.executions.TaskRunStatus;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.worker.WorkerTaskResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Execution-level state machine.
 *
 * <p>This service calculates state and orchestration only. It performs no
 * repository access and never executes a Task implementation.</p>
 */
@Singleton
public final class ExecutorService {

    /**
     * Calculates and creates one TaskRun from the current runnable set. This is
     * the only method allowed to create TaskRuns.
     */
    public Optional<NextTask> handleNext(ExecutorContext<?, ?> context) {
        Execution execution = context.execution();
        if (execution.status() == ExecutionStatus.COMPLETED
            || execution.status() == ExecutionStatus.FAILED
            || execution.status() == ExecutionStatus.CANCELED) {
            return Optional.empty();
        }

        List<Task> tasks = context.flow().tasks();
        SearchResult result = searchTopLevel(context, tasks);
        if (result.candidate() != null) {
            Candidate candidate = result.candidate();
            TaskRun taskRun = execution.createTaskRun(
                candidate.task().id(),
                candidate.parentTaskRunId(),
                candidate.inputs()
            );
            return Optional.of(new NextTask(candidate.task(), taskRun));
        }
        if (result.settled()) {
            execution.complete();
            return Optional.empty();
        }
        if (execution.activeTaskRuns().isEmpty()) {
            throw new WorkflowException(
                "Execution has no runnable Task but the Flow is not settled: "
                    + execution.id()
            );
        }
        return Optional.empty();
    }

    public void dispatch(
        ExecutorContext<?, ?> context,
        NextTask nextTask
    ) {
        context.execution().startTaskRun(nextTask.taskRun().id());
    }

    public void applyResult(
        ExecutorContext<?, ?> context,
        WorkerTaskResult result
    ) {
        Execution execution = context.execution();
        if (!execution.id().equals(result.executionId())) {
            throw new IllegalArgumentException(
                "Worker result belongs to another Execution"
            );
        }
        TaskRun taskRun = execution.requireTaskRun(result.taskRunId());
        if (taskRun.status() != TaskRunStatus.RUNNING) {
            throw new IllegalStateException(
                "Worker result requires a RUNNING TaskRun"
            );
        }
        switch (result.outcome()) {
            case COMPLETED -> execution.completeTaskRun(
                taskRun.id(),
                result.outputs()
            );
            case RUNNING -> {
                // A PAUSE-style Task stays RUNNING until a trigger resumes it.
            }
            case FAILED -> execution.failTaskRun(
                taskRun.id(),
                result.error()
            );
        }
    }

    public void resume(
        ExecutorContext<?, ?> context,
        String taskRunId,
        Map<String, ?> outputs
    ) {
        context.execution().completeTaskRun(taskRunId, outputs);
    }

    public void cancel(ExecutorContext<?, ?> context) {
        context.execution().cancel();
    }

    private static SearchResult searchTopLevel(
        ExecutorContext<?, ?> context,
        List<Task> tasks
    ) {
        Execution execution = context.execution();
        for (Task task : tasks) {
            Optional<TaskRun> taskRun =
                execution.latestTaskRunForTask(task.id());
            if (taskRun.isEmpty()) {
                if (dependenciesCompleted(context, task)) {
                    return SearchResult.candidate(
                        candidate(context, task, null, Map.of())
                    );
                }
                return SearchResult.unsettled();
            }
            TaskRun existing = taskRun.orElseThrow();
            if (existing.status() != TaskRunStatus.COMPLETED) {
                return SearchResult.unsettled();
            }
            SearchResult children = searchChildren(
                context,
                task,
                existing
            );
            if (!children.settled() || children.candidate() != null) {
                return children;
            }
        }
        return SearchResult.settledResult();
    }

    private static SearchResult searchChildren(
        ExecutorContext<?, ?> context,
        Task parent,
        TaskRun parentRun
    ) {
        boolean settled = true;
        for (Task child : parent.tasks()) {
            if (!child.matchesRoute(parentRun.outputs())) {
                continue;
            }
            Optional<TaskRun> childRun = context.execution()
                .latestTaskRunForTask(child.id());
            if (childRun.isEmpty()) {
                settled = false;
                if (dependenciesCompleted(context, child)) {
                    return SearchResult.candidate(candidate(
                        context,
                        child,
                        parentRun.id(),
                        parentRun.outputs()
                    ));
                }
                continue;
            }
            TaskRun existing = childRun.orElseThrow();
            if (existing.status() != TaskRunStatus.COMPLETED) {
                settled = false;
                continue;
            }
            SearchResult descendants = searchChildren(
                context,
                child,
                existing
            );
            if (descendants.candidate() != null) {
                return descendants;
            }
            if (!descendants.settled()) {
                settled = false;
            }
        }
        return settled
            ? SearchResult.settledResult()
            : SearchResult.unsettled();
    }

    private static boolean dependenciesCompleted(
        ExecutorContext<?, ?> context,
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
                || dependencyRun.orElseThrow().status()
                    != TaskRunStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    private static Candidate candidate(
        ExecutorContext<?, ?> context,
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
            inputs.put("dependOnOutputs", Map.copyOf(dependencyOutputs));
        }
        return new Candidate(
            task,
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

    private static final class Candidate {

        private final Task task;
        private final String parentTaskRunId;
        private final Map<String, Object> inputs;

        private Candidate(
            Task task,
            String parentTaskRunId,
            Map<String, Object> inputs
        ) {
            this.task = task;
            this.parentTaskRunId = parentTaskRunId;
            this.inputs = inputs;
        }

        private Task task() {
            return task;
        }

        private String parentTaskRunId() {
            return parentTaskRunId;
        }

        private Map<String, Object> inputs() {
            return inputs;
        }
    }

    private static final class SearchResult {

        private final Candidate candidate;
        private final boolean settled;

        private SearchResult(Candidate candidate, boolean settled) {
            this.candidate = candidate;
            this.settled = settled;
        }

        private static SearchResult candidate(Candidate candidate) {
            return new SearchResult(candidate, false);
        }

        private static SearchResult unsettled() {
            return new SearchResult(null, false);
        }

        private static SearchResult settledResult() {
            return new SearchResult(null, true);
        }

        private Candidate candidate() {
            return candidate;
        }

        private boolean settled() {
            return settled;
        }
    }
}
