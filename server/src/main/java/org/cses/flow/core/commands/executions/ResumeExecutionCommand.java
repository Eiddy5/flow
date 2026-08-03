package org.cses.flow.core.commands.executions;

import org.cses.flow.core.commands.Command;
import org.cses.flow.core.domains.executions.Execution;

import java.util.Map;

/**
 * Resumes one running PAUSE TaskRun in an Execution.
 */
public final class ResumeExecutionCommand implements Command<Execution> {

    private final String executionId;
    private final String taskRunId;
    private final Map<String, Object> outputs;

    public ResumeExecutionCommand(
        String executionId,
        String taskRunId,
        Map<String, ?> outputs
    ) {
        this.executionId = executionId;
        this.taskRunId = taskRunId;
        this.outputs = outputs == null ? null : Map.copyOf(outputs);
    }

    public String executionId() {
        return executionId;
    }

    public String taskRunId() {
        return taskRunId;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    @Override
    public void validate() {
        requireText(executionId, "Execution id");
        requireText(taskRunId, "TaskRun id");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                field + " must not be blank"
            );
        }
    }
}
