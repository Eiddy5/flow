package org.flow.core.models.hierarchies;

import java.util.List;

import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.tasks.Task;

import lombok.Getter;

@Getter
public class GraphTask extends AbstractGraphTask {
    public GraphTask(String uid, Task task, TaskRun taskRun, List<String> values, RelationType relationType) {
        super(uid, task, taskRun, values, relationType);
    }

    public GraphTask(Task task, TaskRun taskRun, List<String> values, RelationType relationType) {
        super(task, taskRun, values, relationType);
    }
}
