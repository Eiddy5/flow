package org.cses.flow.infrastructure.memory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.cses.flow.task.model.Task;
import org.cses.flow.task.repository.TaskRepository;

public final class InMemoryTaskRepository implements TaskRepository {

    private final ConcurrentMap<String, Task> tasks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> insertionOrder = new CopyOnWriteArrayList<>();

    @Override
    public Task save(Task task) {
        if (tasks.put(task.id(), task) == null) {
            insertionOrder.add(task.id());
        }
        return task;
    }

    @Override
    public Optional<Task> findById(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public List<Task> findByProcessId(String processId) {
        return insertionOrder.stream()
                .map(tasks::get)
                .filter(task -> task.processId().equals(processId))
                .toList();
    }
}
