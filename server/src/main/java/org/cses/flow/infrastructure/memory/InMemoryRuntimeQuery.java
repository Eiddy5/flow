package org.cses.flow.infrastructure.memory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.Task;
import org.cses.flow.runtime.query.RuntimeQuery;

public final class InMemoryRuntimeQuery implements RuntimeQuery {

    private final InMemoryRuntimeState state;

    public InMemoryRuntimeQuery(InMemoryRuntimeState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public Optional<Process> findProcessById(String processId) {
        return Optional.ofNullable(state.snapshot().processes.get(processId));
    }

    @Override
    public Optional<Task> findTaskById(String taskId) {
        return Optional.ofNullable(state.snapshot().tasks.get(taskId));
    }

    @Override
    public List<Activity> findActivitiesByProcessId(String processId) {
        return state.snapshot().activities.values().stream()
                .filter(activity -> activity.processId().equals(processId))
                .toList();
    }

    @Override
    public List<Task> findTasksByProcessId(String processId) {
        return state.snapshot().tasks.values().stream()
                .filter(task -> task.processId().equals(processId))
                .toList();
    }
}
