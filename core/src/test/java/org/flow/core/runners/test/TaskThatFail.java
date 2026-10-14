package org.flow.core.runners.test;

import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.annotations.PluginProperty;
import org.flow.core.models.tasks.*;
import org.flow.core.runners.RunContext;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin
public class TaskThatFail extends Task implements RunnableTask<TaskThatFail.Output> {
    @NotNull
    @PluginProperty(dynamic = true)
    private String message;

    @Override
    public TaskThatFail.Output run(RunContext runContext) throws Exception {
        var output = Output.builder().message(this.message).build();
        throw new RunnableTaskException("An exception occurs", output);
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        private String message;
    }
}
