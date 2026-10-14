package org.flow.plugin.core.storage;

import java.net.URI;
import java.util.List;

import org.flow.core.models.annotations.Example;
import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
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
    title = "Purge files created by the current Execution.",
    description = """
        Deletes all internal-storage files produced by this Execution (inputs, outputs, triggers). No-op if nothing was generated."""
)
@Plugin(
    examples = {
        @Example(
            title = "Purge all files created by this execution.",
            full = true,
            code = """
                    id: purge_execution_files
                    namespace: company.team

                    tasks:
                      - id: download
                        type: org.flow.plugin.core.http.Download
                        uri: https://example.com/user_events.json

                      - id: purge
                        type: org.flow.plugin.core.storage.PurgeCurrentExecutionFiles
                """
        )
    }
)
public class PurgeCurrentExecutionFiles extends Task implements RunnableTask<PurgeCurrentExecutionFiles.Output> {
    @Override
    public PurgeCurrentExecutionFiles.Output run(RunContext runContext) throws Exception {
        return Output.builder()
            .uris(runContext.storage().deleteExecutionFiles())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        @Schema(
            title = "The deleted file URIs from Flow's internal storage"
        )
        private final List<URI> uris;
    }
}
