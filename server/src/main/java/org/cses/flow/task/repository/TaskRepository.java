package org.cses.flow.task.repository;

import java.util.List;
import java.util.Optional;
import org.cses.flow.task.model.Task;

public interface TaskRepository {

    Task save(Task task);

    Optional<Task> findById(String taskId);

    List<Task> findByProcessId(String processId);
}
