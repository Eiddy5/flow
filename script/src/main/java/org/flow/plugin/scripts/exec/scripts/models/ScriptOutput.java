package org.flow.plugin.scripts.exec.scripts.models;

import java.net.URI;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import org.flow.plugin.api.annotations.PluginProperty;
import org.flow.core.models.tasks.Output;
import org.flow.core.models.tasks.runners.TaskRunnerDetailResult;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
public class ScriptOutput implements Output {
    @Schema(
        title = "The values extracted from executed `commands` using the Flow outputs format."
    )
    @JsonInclude(JsonInclude.Include.ALWAYS) // always include vars so it's easier to reason about in expressions
    private final Map<String, Object> vars;

    @Schema(
        title = "The exit code of the entire flow execution."
    )
    @NotNull
    private final int exitCode;

    @Schema(
        title = "The output files' URIs in Flow's internal storage."
    )
    @PluginProperty(additionalProperties = URI.class)
    private final Map<String, URI> outputFiles;

    @JsonIgnore
    private final int stdOutLineCount;

    @JsonIgnore
    private final int stdErrLineCount;

    private TaskRunnerDetailResult taskRunner;

}
