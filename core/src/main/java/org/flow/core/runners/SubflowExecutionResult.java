package org.flow.core.runners;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.flow.core.models.HasUID;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.State;
import org.flow.core.queues.event.DispatchEvent;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SubflowExecutionResult implements HasUID, DispatchEvent {
    @NotNull
    private TaskRun parentTaskRun;

    @NotNull
    private String executionId;

    @NotNull
    private State.Type state;

    @Nullable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Map<String, Object> outputs;

    @Override
    public String key() {
        return executionId;
    }

    @Override
    public String uid() {
        return executionId;
    }
}
