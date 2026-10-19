package org.flow.plugin.core.storage;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

import org.flow.plugin.api.annotations.Example;
import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static org.flow.core.utils.Rethrow.throwFunction;
import static org.flow.core.utils.Rethrow.throwSupplier;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Write data to a file in Flow internal storage.",
    description = """
        Renders `content`, writes it to a temp file (optionally with `extension`), and uploads it to internal storage, returning the `flow://` URI.

        Handy for generating small artifacts that downstream tasks can consume via URI."""
)
@Plugin(
    examples = {
        @Example(
            title = "Write data to a file in the internal storage.",
            full = true,
            code = """
                id: write_file
                namespace: company.team

                tasks:
                  - id: write
                    type: org.flow.plugin.core.storage.Write
                    content: Hello World
                    extension: .txt
                """
        )
    }
)
public class Write extends Task implements RunnableTask<Write.Output> {
    @Schema(title = "The file content")
    @NotNull
    private Property<String> content;

    @Schema(title = "The file extension")
    private Property<String> extension;

    @Override
    public Write.Output run(RunContext runContext) throws Exception {
        byte[] bytes = runContext.render(content).as(String.class).map(s -> s.getBytes()).orElseThrow();
        Optional<String> maybeExtension = runContext.render(extension).as(String.class);
        Path path = maybeExtension.map(throwFunction(ext -> runContext.workingDir().createTempFile(bytes, ext)))
            .orElseGet(throwSupplier(() -> runContext.workingDir().createTempFile(bytes)));

        return Write.Output.builder()
            .uri(runContext.storage().putFile(path.toFile()))
            .build();
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        @Schema(
            title = "The created file URI"
        )
        private final URI uri;
    }
}
