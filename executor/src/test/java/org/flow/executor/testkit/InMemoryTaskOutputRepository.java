package org.flow.executor.testkit;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskOutput;
import org.flow.core.repositories.TaskOutputRepositoryInterface;

/**
 * Map-backed {@link TaskOutputRepositoryInterface} so the real
 * {@link org.flow.core.services.TaskOutputService} can run without a database.
 */
public class InMemoryTaskOutputRepository implements TaskOutputRepositoryInterface {
    private final Map<String, TaskOutput> outputs = new ConcurrentHashMap<>();

    @Override
    public Optional<TaskOutput> findById(String tenantId, String taskRunId) {
        return Optional.ofNullable(outputs.get(key(tenantId, taskRunId)));
    }

    @Override
    public TaskOutput save(TaskOutput taskOutput) {
        outputs.put(key(taskOutput.tenantId(), taskOutput.taskRunId()), taskOutput);
        return taskOutput;
    }

    @Override
    public List<TaskOutput> findByExecution(Execution execution) {
        return outputs.values().stream()
            .filter(output -> execution.getId().equals(output.executionId()))
            .toList();
    }

    @Override
    public int purgeByExecutionIds(List<String> executionIds) {
        int before = outputs.size();
        outputs.values().removeIf(output -> executionIds.contains(output.executionId()));
        return before - outputs.size();
    }

    private static String key(String tenantId, String taskRunId) {
        return tenantId + "|" + taskRunId;
    }
}
