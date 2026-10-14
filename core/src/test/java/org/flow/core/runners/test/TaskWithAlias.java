package org.flow.core.runners.test;

import org.slf4j.Logger;

import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.annotations.PluginProperty;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.tasks.VoidOutput;
import org.flow.core.runners.RunContext;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor

@Plugin(
    aliases = "org.flow.core.runners.test.task.Alias"
)
public class TaskWithAlias extends Task implements RunnableTask<VoidOutput> {
    @NotNull
    @PluginProperty(dynamic = true)
    private String message;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        logger.info(message);

        return null;
    }
}
