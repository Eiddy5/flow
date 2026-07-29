package org.cses.flow.core.domains.executions;

import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.paas.common.util.StringUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root for one complete Flow start instance.
 */
public final class Execution {

    private final String id;
    private final String companyId;
    private final String flowId;
    private final long flowReversion;
    private final List<TaskRun> taskRuns;
    private final boolean persisted;
    private ExecutionStatus status;
    private long lockVersion;
    private boolean modified;

    private Execution(
        String id,
        String companyId,
        String flowId,
        long flowReversion,
        boolean persisted
    ) {
        this.id = requireText(id, "Execution id");
        this.companyId = requireText(companyId, "Company id");
        this.flowId = requireText(flowId, "Flow id");
        if (flowReversion < 1) {
            throw new IllegalArgumentException(
                "Flow reversion must be positive"
            );
        }
        this.flowReversion = flowReversion;
        this.taskRuns = new ArrayList<>();
        this.status = ExecutionStatus.CREATED;
        this.persisted = persisted;
    }

    public static Execution create(
        String companyId,
        String flowId,
        long flowReversion
    ) {
        return new Execution(
            StringUtil.newId(),
            companyId,
            flowId,
            flowReversion,
            false
        );
    }

    /**
     * Rehydrates a complete aggregate from a trusted persistence adapter.
     */
    public static Execution rehydrate(
        String id,
        String companyId,
        String flowId,
        long flowReversion,
        ExecutionStatus status,
        long lockVersion,
        List<TaskRun> taskRuns
    ) {
        if (lockVersion < 0) {
            throw new IllegalArgumentException(
                "Execution lock version must not be negative"
            );
        }
        Execution execution = new Execution(
            id,
            companyId,
            flowId,
            flowReversion,
            true
        );
        execution.status = Objects.requireNonNull(
            status,
            "Execution status"
        );
        execution.lockVersion = lockVersion;
        if (taskRuns != null) {
            taskRuns.stream()
                .map(TaskRun::copy)
                .forEach(execution.taskRuns::add);
        }
        execution.validateRehydratedState();
        return execution;
    }

    private Execution(Execution source) {
        this.id = source.id;
        this.companyId = source.companyId;
        this.flowId = source.flowId;
        this.flowReversion = source.flowReversion;
        this.taskRuns = source.taskRuns.stream()
            .map(TaskRun::copy)
            .collect(java.util.stream.Collectors.toCollection(
                ArrayList::new
            ));
        this.status = source.status;
        this.lockVersion = source.lockVersion;
        this.persisted = source.persisted;
        this.modified = source.modified;
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String flowId() {
        return flowId;
    }

    public long flowReversion() {
        return flowReversion;
    }

    public ExecutionStatus status() {
        return status;
    }

    public long lockVersion() {
        return lockVersion;
    }

    public List<TaskRun> taskRuns() {
        return List.copyOf(taskRuns);
    }

    public Optional<TaskRun> findTaskRun(String taskRunId) {
        String normalizedId = requireText(taskRunId, "TaskRun id");
        return taskRuns.stream()
            .filter(taskRun -> taskRun.id().equals(normalizedId))
            .findFirst();
    }

    public List<TaskRun> taskRunsForTask(String taskId) {
        String normalizedId = requireText(taskId, "Task id");
        return taskRuns.stream()
            .filter(taskRun -> taskRun.taskId().equals(normalizedId))
            .toList();
    }

    public Optional<TaskRun> latestTaskRunForTask(String taskId) {
        List<TaskRun> matches = taskRunsForTask(taskId);
        return matches.isEmpty()
            ? Optional.empty()
            : Optional.of(matches.getLast());
    }

    public List<TaskRun> activeTaskRuns() {
        return taskRuns.stream()
            .filter(TaskRun::isActive)
            .toList();
    }

    public Optional<TaskRun> lastTaskRun() {
        return taskRuns.isEmpty()
            ? Optional.empty()
            : Optional.of(taskRuns.getLast());
    }

    public boolean isTerminal() {
        return status == ExecutionStatus.COMPLETED
            || status == ExecutionStatus.FAILED
            || status == ExecutionStatus.CANCELED;
    }

    public TaskRun createTaskRun(
        String taskId,
        String parentId,
        Map<String, ?> inputs
    ) {
        if (status != ExecutionStatus.CREATED
            && status != ExecutionStatus.RUNNING) {
            throw new WorkflowException(
                "Execution cannot create TaskRun from " + status + ": " + id
            );
        }
        if (latestTaskRunForTask(taskId).isPresent()) {
            throw new WorkflowException(
                "Execution already has a TaskRun for Task: " + taskId
            );
        }
        String normalizedParentId =
            parentId == null || parentId.isBlank()
                ? null
                : parentId.trim();
        if (normalizedParentId != null
            && findTaskRun(normalizedParentId).isEmpty()) {
            throw new WorkflowException(
                "Parent TaskRun does not exist: " + normalizedParentId
            );
        }
        TaskRun taskRun = new TaskRun(
            StringUtil.newId(),
            taskId,
            normalizedParentId,
            inputs
        );
        markModified();
        taskRuns.add(taskRun);
        return taskRun;
    }

    public void startTaskRun(String taskRunId) {
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunStatus(taskRun, TaskRunStatus.CREATED);
        markModified();
        taskRun.start();
        if (status == ExecutionStatus.CREATED) {
            status = ExecutionStatus.RUNNING;
        }
    }

    public void completeTaskRun(
        String taskRunId,
        Map<String, ?> outputs
    ) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunStatus(taskRun, TaskRunStatus.RUNNING);
        markModified();
        taskRun.complete(outputs);
    }

    public void failTaskRun(String taskRunId, String error) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunStatus(taskRun, TaskRunStatus.RUNNING);
        if (error == null || error.isBlank()) {
            throw new IllegalArgumentException(
                "TaskRun error must not be blank"
            );
        }
        markModified();
        taskRun.fail(error);
        status = ExecutionStatus.FAILED;
    }

    public void complete() {
        requireRunning();
        if (!activeTaskRuns().isEmpty()) {
            throw new WorkflowException(
                "Execution has unfinished TaskRun: " + id
            );
        }
        markModified();
        status = ExecutionStatus.COMPLETED;
    }

    public void cancel() {
        requireRunning();
        markModified();
        taskRuns.stream()
            .filter(TaskRun::isActive)
            .forEach(TaskRun::cancel);
        status = ExecutionStatus.CANCELED;
    }

    public TaskRun requireTaskRun(String taskRunId) {
        return findTaskRun(taskRunId)
            .orElseThrow(() ->
                new WorkflowException("TaskRun does not exist: " + taskRunId)
            );
    }

    public Execution copy() {
        return new Execution(this);
    }

    private void markModified() {
        if (persisted && !modified) {
            lockVersion++;
            modified = true;
        }
    }

    private void validateRehydratedState() {
        HashSet<String> ids = new HashSet<>();
        for (TaskRun taskRun : taskRuns) {
            if (!ids.add(taskRun.id())) {
                throw new IllegalArgumentException(
                    "Execution has duplicate TaskRun id: " + taskRun.id()
                );
            }
            taskRun.parentId().ifPresent(parentId -> {
                if (!ids.contains(parentId)) {
                    throw new IllegalArgumentException(
                        "TaskRun parent must precede child in Execution: "
                            + taskRun.id()
                    );
                }
            });
        }
        if (status == ExecutionStatus.COMPLETED
            && !activeTaskRuns().isEmpty()) {
            throw new IllegalArgumentException(
                "Completed Execution must not have active TaskRuns"
            );
        }
    }

    private void requireRunning() {
        if (status != ExecutionStatus.RUNNING) {
            throw new WorkflowException(
                "Execution must be RUNNING but was " + status + ": " + id
            );
        }
    }

    private static void requireTaskRunStatus(
        TaskRun taskRun,
        TaskRunStatus expected
    ) {
        if (taskRun.status() != expected) {
            throw new WorkflowException(
                "TaskRun " + taskRun.id() + " must be " + expected
                    + " but was " + taskRun.status()
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                field + " must not be blank"
            );
        }
        return value.trim();
    }
}
