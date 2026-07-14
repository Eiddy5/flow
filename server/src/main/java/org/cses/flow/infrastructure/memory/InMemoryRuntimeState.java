package org.cses.flow.infrastructure.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.Task;

public final class InMemoryRuntimeState {

    private Snapshot committed = Snapshot.empty();
    private long committedTransactionCount;

    synchronized Snapshot snapshot() {
        return committed.copy();
    }

    synchronized void publish(Snapshot snapshot) {
        committed = snapshot.copy();
        committedTransactionCount++;
    }

    public synchronized long committedTransactionCount() {
        return committedTransactionCount;
    }

    public synchronized int processCount() { return committed.processes.size(); }
    public synchronized int activityCount() { return committed.activities.size(); }
    public synchronized int taskCount() { return committed.tasks.size(); }

    static final class Snapshot {

        final Map<String, Process> processes;
        final Map<String, Activity> activities;
        final Map<String, Task> tasks;

        private Snapshot(
                Map<String, Process> processes,
                Map<String, Activity> activities,
                Map<String, Task> tasks) {
            this.processes = processes;
            this.activities = activities;
            this.tasks = tasks;
        }

        static Snapshot empty() {
            return new Snapshot(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        Snapshot copy() {
            Map<String, Process> processCopies = new LinkedHashMap<>();
            processes.forEach((id, process) -> processCopies.put(id, process.copy()));
            Map<String, Activity> activityCopies = new LinkedHashMap<>();
            activities.forEach((id, activity) -> activityCopies.put(id, activity.copy()));
            Map<String, Task> taskCopies = new LinkedHashMap<>();
            tasks.forEach((id, task) -> taskCopies.put(id, task.copy()));
            return new Snapshot(processCopies, activityCopies, taskCopies);
        }
    }
}
