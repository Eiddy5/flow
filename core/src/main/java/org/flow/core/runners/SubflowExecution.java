package org.flow.core.runners;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.flow.core.models.HasUID;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.tasks.ExecutableTask;
import org.flow.core.models.tasks.Task;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.With;

@Data
@Builder
public class SubflowExecution<T extends Task & ExecutableTask<?>> implements HasUID {
    @NotNull
    private TaskRun parentTaskRun;

    @NotNull
    private T parentTask;

    @NotNull
    private Execution execution;

    @Nullable
    @With
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Map<String, Object> outputs;

    @Override
    public String uid() {
        return execution.getId();
    }
}
