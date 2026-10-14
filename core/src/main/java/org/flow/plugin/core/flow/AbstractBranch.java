package org.flow.plugin.core.flow;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.models.annotations.PluginProperty;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.tasks.*;
import org.flow.core.runners.FlowableUtils;
import org.flow.core.runners.RunContext;
import org.flow.core.utils.ListUtils;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
abstract class AbstractBranch<T extends Output> extends Task implements FlowableTask<T> {
    @Valid
    protected List<Task> errors;

    @Valid
    @JsonProperty("finally")
    @Getter(AccessLevel.NONE)
    protected List<Task> _finally;

    public List<Task> getFinally() {
        return this._finally;
    }

    @Valid
    @PluginProperty
    @NotEmpty(message = "The 'tasks' property cannot be empty")
    protected List<Task> tasks;

    @Override
    public List<Task> allChildTasks() {
        return ListUtils.concat(tasks, errors, _finally);
    }

    @Override
    public List<ResolvedTask> childTasks(RunContext runContext, TaskRun parentTaskRun) throws IllegalVariableEvaluationException {
        return FlowableUtils.resolveTasks(this.getTasks(), parentTaskRun);
    }
}
