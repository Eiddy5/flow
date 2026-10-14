package org.flow.core.tasks.test;

import org.flow.core.models.annotations.Example;
import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.annotations.PluginProperty;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.tasks.common.EncryptedString;
import org.flow.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Debugging task that returns an encrypted value."
)
@Plugin(
    examples = {
        @Example(
            code = "format: \"Hello World\""
        )
    }
)
public class Encrypted extends Task implements RunnableTask<Encrypted.Output> {
    @Schema(
        title = "The templated string to encrypt."
    )
    @PluginProperty(dynamic = true)
    private String format;

    @Override
    public Encrypted.Output run(RunContext runContext) throws Exception {
        return Encrypted.Output.builder()
            .value(EncryptedString.from(format, runContext))
            .build();
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        @Schema(
            title = "The encrypted string."
        )
        private EncryptedString value;
    }
}