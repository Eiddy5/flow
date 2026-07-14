package org.cses.flow.runtime.query;

import java.util.List;
import java.util.Optional;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.Task;

public interface RuntimeQuery {

    Optional<Process> findProcessById(String processId);

    Optional<Task> findTaskById(String taskId);

    List<Activity> findActivitiesByProcessId(String processId);

    List<Task> findTasksByProcessId(String processId);
}
