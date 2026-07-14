package org.cses.flow.infrastructure.memory;

import org.cses.flow.runtime.context.ResumeTarget;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.Task;
import org.cses.flow.runtime.session.RuntimeSession;

public final class InMemoryRuntimeSession implements RuntimeSession {

    private final InMemoryRuntimeState.Snapshot state;

    InMemoryRuntimeSession(InMemoryEngineTransaction transaction) {
        this.state = transaction.snapshot();
    }

    @Override
    public ResumeTarget loadResumeTarget(String taskId) {
        Task task = require(state.tasks.get(taskId), "Task", taskId);
        Process process = require(state.processes.get(task.processId()), "Process", task.processId());
        Activity activity = require(state.activities.get(task.activityId()), "Activity", task.activityId());
        if (!activity.processId().equals(process.id())
                || !activity.executorId().equals(task.executorId())
                || !activity.nodeId().equals(task.nodeId())) {
            throw new IllegalStateException("Task runtime relations do not match: " + taskId);
        }
        return new ResumeTarget(process, process.executor(task.executorId()), activity, task);
    }

    @Override
    public void insert(Process process) { insertUnique(state.processes, process.id(), process, "Process"); }

    @Override
    public void insert(Activity activity) { insertUnique(state.activities, activity.id(), activity, "Activity"); }

    @Override
    public void insert(Task task) { insertUnique(state.tasks, task.id(), task, "Task"); }

    @Override
    public void update(Process process) { updateExisting(state.processes, process.id(), process, "Process"); }

    @Override
    public void update(Activity activity) { updateExisting(state.activities, activity.id(), activity, "Activity"); }

    @Override
    public void update(Task task) { updateExisting(state.tasks, task.id(), task, "Task"); }

    private <T> void insertUnique(java.util.Map<String, T> values, String id, T value, String type) {
        if (values.putIfAbsent(id, value) != null) {
            throw new IllegalStateException(type + " already exists: " + id);
        }
    }

    private <T> void updateExisting(java.util.Map<String, T> values, String id, T value, String type) {
        if (values.replace(id, value) == null) {
            throw new IllegalStateException(type + " does not exist: " + id);
        }
    }

    private <T> T require(T value, String type, String id) {
        if (value == null) {
            throw new IllegalArgumentException(type + " not found: " + id);
        }
        return value;
    }
}
