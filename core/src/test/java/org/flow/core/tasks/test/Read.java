package org.flow.core.tasks.test;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.flow.core.models.annotations.PluginProperty;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public class Read extends Task implements RunnableTask<Read.Output> {
    @PluginProperty
    @NotNull
    private String path;

    @Override
    public Read.Output run(RunContext runContext) throws Exception {
        return Output.builder()
            .value(Files.readString(Paths.get(runContext.workingDir().path().toString(), runContext.render(path))))
            .build();
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        @Schema(
            title = "The file contents"
        )
        private String value;
    }
}
