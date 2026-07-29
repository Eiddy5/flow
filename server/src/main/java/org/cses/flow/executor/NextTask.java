package org.cses.flow.executor;

import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.tasks.Task;

import java.util.Objects;

public final class NextTask {

    private final Task task;
    private final TaskRun taskRun;

    public NextTask(Task task, TaskRun taskRun) {
        this.task = Objects.requireNonNull(task, "task");
        this.taskRun = Objects.requireNonNull(taskRun, "taskRun");
    }

    public Task task() {
        return task;
    }

    public TaskRun taskRun() {
        return taskRun;
    }
}
