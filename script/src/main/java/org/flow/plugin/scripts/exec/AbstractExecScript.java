package org.flow.plugin.scripts.exec;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.plugin.api.annotations.PluginProperty;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.*;
import org.flow.core.models.tasks.runners.TargetOS;
import org.flow.core.models.tasks.runners.TaskRunner;
import org.flow.core.runners.RunContext;
import org.flow.plugin.scripts.exec.scripts.runners.CommandsWrapper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractExecScript extends Task implements NamespaceFilesInterface, InputFilesInterface, OutputFilesInterface {
    @Schema(
        title = "The task runner to use.",
        description = "Required. Select a task runner explicitly, such as org.flow.plugin.core.runner.Process. Each runner plugin has its own properties."
    )
    @PluginProperty
    @NotNull
    @Valid
    protected TaskRunner<?> taskRunner;

    @Schema(
        title = "A list of commands that will run before the `commands`, allowing to set up the environment e.g. `pip install -r requirements.txt`."
    )
    protected Property<List<String>> beforeCommands;

    @Schema(
        title = "Additional environment variables for the current process."
    )
    protected Property<Map<String, String>> env;

    @Builder.Default
    @Schema(
        title = "Which interpreter to use."
    )
    @PluginProperty(dynamic = true)
    @NotNull
    protected Property<List<String>> interpreter = Property.ofValue(List.of("/bin/sh", "-c"));

    @Builder.Default
    @Schema(
        title = "Fail the task on the first command with a non-zero status.",
        description = "If set to `false` all commands will be executed one after the other. The final state of task execution is determined by the last command. Note that this property maybe be ignored if a non compatible interpreter is specified."
            +
            "\nYou can also disable it if your interpreter does not support the `set -e`option."
    )
    protected Property<Boolean> failFast = Property.ofValue(true);

    private NamespaceFiles namespaceFiles;

    private Object inputFiles;

    @Schema(
        title = "List of output file names to capture after execution.",
        description = """
            Declares files produced by the script that should be uploaded to Flow's internal storage.
            Use `{{ outputFiles["filename"] }}` in your script body to get the resolved absolute path — this works for local runners (Process) and remote runners (Kubernetes, cloud batch).
            Glob patterns (e.g. `*.csv`) are supported for post-run collection but cannot be referenced via Pebble expressions inside the script."""
    )
    private Property<List<String>> outputFiles;

    @Schema(
        title = "Whether to setup the output directory mechanism.",
        description = "Required to use the {{ outputDir }} expression. Note that it could increase the starting time. Deprecated, use the `outputFiles` property instead.",
        defaultValue = "false",
        deprecated = true
    )
    @Deprecated
    private Property<Boolean> outputDirectory;

    @Schema(
        title = "Whether to write the execution context to a `.flow-execution-context.json` file in the working directory.",
        description = """
            Gives the script its execution metadata — `inputs`, `outputs`, `labels`, `vars`, `trigger`, `flow`, `execution` — as JSON data instead of Pebble expressions interpolated into its source, which keeps the script valid, lintable code in an IDE and safe for values holding quotes or newlines.
            The Python client reads it with `from flow import context`, then `context.outputs.previous_task.uri`.
            Disabled by default: the file holds the same values the task could already render, secret-typed inputs included, so it is only written when asked for.""",
        defaultValue = "false"
    )
    private Property<Boolean> executionContext;

    @Schema(
        title = "The target operating system where the script will run."
    )
    @Builder.Default
    @NotNull
    protected Property<TargetOS> targetOS = Property.ofValue(TargetOS.AUTO);

    @Schema(
        title = "The task runner container image, only used if the task runner is container-based."
    )
    public abstract Property<String> getContainerImage();

    protected CommandsWrapper commands(RunContext runContext) throws IllegalVariableEvaluationException {
        Map<String, String> renderedEnv = runContext.render(this.getEnv()).asMap(String.class, String.class);
        return new CommandsWrapper(runContext)
            .withEnv(renderedEnv.isEmpty() ? new HashMap<>() : renderedEnv)
            .withContainerImage(runContext.render(this.getContainerImage()).as(String.class).orElse(null))
            .withTaskRunner(this.getTaskRunner())
            .withNamespaceFiles(this.getNamespaceFiles())
            .withInputFiles(this.getInputFiles())
            .withOutputFiles(runContext.render(this.getOutputFiles()).asList(String.class))
            .withEnableOutputDirectory(runContext.render(this.getOutputDirectory()).as(Boolean.class).orElse(null))
            .withTimeout(runContext.render(this.getTimeout()).as(Duration.class).orElse(null))
            .withTargetOS(runContext.render(this.getTargetOS()).as(TargetOS.class).orElseThrow())
            .withFailFast(runContext.render(this.getFailFast()).as(Boolean.class).orElse(false))
            .withExecutionContext(runContext.render(this.getExecutionContext()).as(Boolean.class).orElse(false));
    }

    public void kill() {
        if (this.getTaskRunner() != null) {
            this.getTaskRunner().kill();
        }
    }
}
