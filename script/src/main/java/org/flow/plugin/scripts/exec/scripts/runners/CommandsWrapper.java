package org.flow.plugin.scripts.exec.scripts.runners;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import org.apache.commons.lang3.SystemUtils;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.NamespaceFiles;
import org.flow.core.models.tasks.RunnableTaskException;
import org.flow.core.models.tasks.runners.*;
import org.flow.core.models.tasks.runners.DefaultLogConsumer;
import org.flow.core.runners.FilesService;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunVariables;
import org.flow.core.runners.WorkingDir;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.utils.IdUtils;
import org.flow.core.utils.NamespaceFilesUtils;
import org.flow.plugin.core.runner.Process;
import org.flow.plugin.scripts.exec.scripts.models.ScriptOutput;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.With;

import static org.flow.core.utils.Rethrow.throwFunction;

@AllArgsConstructor
@Getter
public class CommandsWrapper implements TaskCommands {
    private static final Set<String> EXECUTION_CONTEXT_EXCLUDED_VARIABLES = Set.of(
        // already handed to the script as environment variables
        "envs",
        "globals",
        // internal plumbing, not execution metadata
        RunVariables.SECRET_CONSUMER_VARIABLE_NAME,
        RunVariables.FIXTURE_FILES_KEY
    );

    private RunContext runContext;

    private Path workingDirectory;

    private Path outputDirectory;

    private Map<String, Object> additionalVars;

    @With
    private Property<List<String>> interpreter;

    @With
    private Property<List<String>> beforeCommands;

    @With
    private Property<List<String>> commands;

    @With
    private boolean beforeCommandsWithOptions;

    @With
    private boolean failFast;

    private Map<String, String> env;

    @With
    private org.flow.core.models.tasks.runners.AbstractLogConsumer logConsumer;

    @With
    private String containerImage;

    @With
    private TaskRunner<?> taskRunner;

    @With
    private NamespaceFiles namespaceFiles;

    @With
    private Object inputFiles;

    @With
    private List<String> outputFiles;

    @With
    private Boolean enableOutputDirectory;

    @With
    private Duration timeout;

    @With
    private TargetOS targetOS;

    @With
    private boolean executionContext;

    public CommandsWrapper(RunContext runContext) {
        this.runContext = runContext;
        this.workingDirectory = runContext.workingDir().path();
        this.logConsumer = new DefaultLogConsumer(runContext);
        this.additionalVars = new HashMap<>();
        this.env = new HashMap<>();
    }

    public CommandsWrapper withEnv(Map<String, String> envs) {
        return new CommandsWrapper(
            runContext,
            workingDirectory,
            getOutputDirectory(),
            additionalVars,
            interpreter,
            beforeCommands,
            commands,
            beforeCommandsWithOptions,
            failFast,
            envs,
            logConsumer,
            containerImage,
            taskRunner,
            namespaceFiles,
            inputFiles,
            outputFiles,
            enableOutputDirectory,
            timeout,
            targetOS,
            executionContext
        );
    }

    public CommandsWrapper addAdditionalVars(Map<String, Object> additionalVars) {
        if (this.additionalVars == null) {
            this.additionalVars = new HashMap<>();
        }
        this.additionalVars.putAll(additionalVars);

        return this;
    }

    public CommandsWrapper addEnv(Map<String, String> envs) {
        if (this.env == null) {
            this.env = new HashMap<>();
        }
        this.env.putAll(envs);

        return this;
    }

    /**
     * Writes the execution context as JSON into the working directory, so a script can read its
     * metadata as data instead of interpolating Pebble expressions into its own source.
     *
     * `envs` and `globals` are left out: both are already exposed to the script as environment
     * variables, so writing them again would only widen what ends up on disk.
     */
    private void writeExecutionContextFile() throws IOException {
        Map<String, Object> variables = new TreeMap<>(runContext.getVariables());
        EXECUTION_CONTEXT_EXCLUDED_VARIABLES.forEach(variables::remove);

        Files.write(
            this.workingDirectory.resolve(WorkingDir.EXECUTION_CONTEXT_FILE_NAME),
            JacksonMapper.ofJson().writeValueAsBytes(variables)
        );
    }

    public <T extends TaskRunnerDetailResult> ScriptOutput run() throws Exception {
        if (this.namespaceFiles != null && !Boolean.FALSE.equals(runContext.render(this.namespaceFiles.getEnabled()).as(Boolean.class).orElse(true))) {
            NamespaceFilesUtils.loadNamespaceFiles(runContext, this.namespaceFiles);
        }

        // Inject non-glob outputFiles into the Pebble render context as a name→path map before any rendering.
        // This makes {{ outputFiles["name"] }} resolvable in scripts, consistent with JDBC tasks.
        // Glob patterns are skipped here — they cannot resolve to a single path; post-run collection still handles them.
        Map<String, Object> runnerVars = taskRunner.additionalVars(runContext, this);
        if (this.outputFiles != null && !this.outputFiles.isEmpty()) {
            String workingDir = String.valueOf(runnerVars.getOrDefault(ScriptService.VAR_WORKING_DIR, this.workingDirectory));
            Map<String, String> outputFilesMap = new LinkedHashMap<>();
            for (String name : this.outputFiles) {
                if (!name.contains("*") && !name.contains("?") && !name.contains("[")) {
                    outputFilesMap.put(name, workingDir + "/" + name);
                }
            }
            if (!outputFilesMap.isEmpty()) {
                runnerVars.put("outputFiles", outputFilesMap);
            }
        }

        if (this.inputFiles != null) {
            FilesService.inputFiles(runContext, runnerVars, this.inputFiles);
        }

        if (this.executionContext) {
            this.writeExecutionContextFile();
        }

        RunContext taskRunnerRunContext = runContext.cloneForPlugin(taskRunner);

        List<String> renderedCommands = this.renderCommands(runContext, commands);
        List<String> renderedBeforeCommands = this.renderCommands(runContext, beforeCommands);
        List<String> renderedInterpreter = this.renderCommands(runContext, interpreter);

        List<String> effectiveBeforeCommands = this.isBeforeCommandsWithOptions() ? getBeforeCommandsWithOptions(renderedBeforeCommands) : renderedBeforeCommands;
        if (!renderedBeforeCommands.isEmpty()) {
            List<String> marked = new ArrayList<>();
            marked.add("echo '##flow:log:debug##'");
            marked.addAll(effectiveBeforeCommands);
            marked.add("echo '##flow:log:info##'");
            effectiveBeforeCommands = marked;
        }

        List<String> finalCommands = renderedBeforeCommands.isEmpty() && renderedInterpreter.isEmpty() ? renderedCommands
            : ScriptService.scriptCommands(
                renderedInterpreter,
                effectiveBeforeCommands,
                renderedCommands,
                Optional.ofNullable(targetOS).orElse(TargetOS.AUTO)
            );

        this.commands = Property.ofValue(finalCommands);

        ScriptOutput.ScriptOutputBuilder scriptOutputBuilder = ScriptOutput.builder();

        try {
            TaskRunnerResult<T> taskRunnerResult = (TaskRunnerResult<T>) taskRunner.run(taskRunnerRunContext, this, this.outputFiles);
            scriptOutputBuilder.exitCode(taskRunnerResult.getExitCode())
                .outputFiles(getOutputFiles(taskRunnerRunContext))
                .taskRunner(taskRunnerResult.getDetails());

            if (taskRunnerResult.getLogConsumer() != null) {
                scriptOutputBuilder
                    .stdOutLineCount(taskRunnerResult.getLogConsumer().getStdOutCount())
                    .stdErrLineCount(taskRunnerResult.getLogConsumer().getStdErrCount())
                    .vars(taskRunnerResult.getLogConsumer().getOutputs());
            }

            return scriptOutputBuilder.build();
        } catch (TaskException e) {
            var output = scriptOutputBuilder.exitCode(e.getExitCode())
                .stdOutLineCount(e.getStdOutCount())
                .stdErrLineCount(e.getStdErrCount())
                .vars(e.getLogConsumer() != null ? e.getLogConsumer().getOutputs() : null)
                .taskRunner(e.getDetails())
                .outputFiles(getOutputFiles(taskRunnerRunContext))
                .build();
            throw new RunnableTaskException(e, output);
        } finally {
            if (this.executionContext) {
                this.deleteExecutionContextFile();
            }
        }
    }

    /**
     * Removes the execution context file once the commands are done.
     * <p>
     * Inside a {@code WorkingDirectory} the working directory is shared between child tasks, so a
     * leftover file would be picked up by a later task that never set `executionContext` — handing it
     * the previous task's metadata instead of its own.
     */
    private void deleteExecutionContextFile() {
        try {
            Files.deleteIfExists(this.workingDirectory.resolve(WorkingDir.EXECUTION_CONTEXT_FILE_NAME));
        } catch (IOException e) {
            runContext.logger().debug("Unable to delete the execution context file", e);
        }
    }

    private Map<String, URI> getOutputFiles(RunContext taskRunnerRunContext) throws Exception {
        Map<String, URI> outputFiles = new HashMap<>();
        if (this.outputDirectoryEnabled()) {
            outputFiles.putAll(ScriptService.uploadOutputFiles(taskRunnerRunContext, this.getOutputDirectory()));
        }

        if (this.outputFiles != null) {
            outputFiles.putAll(FilesService.outputFiles(taskRunnerRunContext, this.outputFiles));
        }
        return outputFiles;
    }

    public Path getOutputDirectory() {
        if (this.outputDirectory == null) {
            this.outputDirectory = this.workingDirectory.resolve(IdUtils.create());
            if (!this.outputDirectory.toFile().mkdirs()) {
                throw new RuntimeException("Unable to create the output directory " + this.outputDirectory);
            }
        }

        return this.outputDirectory;
    }

    public String render(RunContext runContext, String command, List<String> internalStorageLocalFiles) throws IllegalVariableEvaluationException, IOException {
        TaskRunner<?> taskRunner = this.getTaskRunner();
        return ScriptService.replaceInternalStorage(
            this.runContext,
            taskRunner.additionalVars(runContext, this),
            command,
            taskRunner instanceof RemoteRunnerInterface
        );
    }

    public String render(RunContext runContext, Property<String> command) throws IllegalVariableEvaluationException, IOException {
        TaskRunner<?> taskRunner = this.getTaskRunner();
        if (command == null) {
            return null;
        }

        Map<String, Object> additionalVars = taskRunner.additionalVars(runContext, this);
        if (this.outputFiles != null && !this.outputFiles.isEmpty()) {
            String workingDir = String.valueOf(additionalVars.getOrDefault(ScriptService.VAR_WORKING_DIR, this.workingDirectory));
            Map<String, String> outputFilesMap = new LinkedHashMap<>();
            for (String name : this.outputFiles) {
                if (!name.contains("*") && !name.contains("?") && !name.contains("[")) {
                    outputFilesMap.put(name, workingDir + "/" + name);
                }
            }
            if (!outputFilesMap.isEmpty()) {
                additionalVars.put("outputFiles", outputFilesMap);
            }
        }

        return runContext.render(command).as(String.class, additionalVars)
            .map(throwFunction(c -> ScriptService.replaceInternalStorage(runContext, c, taskRunner instanceof RemoteRunnerInterface)))
            .orElse(null);
    }

    public List<String> renderCommands(RunContext runContext, Property<List<String>> commands) throws IllegalVariableEvaluationException, IOException {
        TaskRunner<?> taskRunner = this.getTaskRunner();
        return ScriptService.replaceInternalStorage(
            this.runContext,
            taskRunner.additionalVars(runContext, this),
            commands,
            taskRunner instanceof RemoteRunnerInterface
        );
    }

    protected List<String> getBeforeCommandsWithOptions(List<String> beforeCommands) throws IllegalVariableEvaluationException {
        if (!this.isFailFast()) {
            return beforeCommands;
        }

        if (beforeCommands == null || beforeCommands.isEmpty()) {
            return getExitOnErrorCommands();
        }

        ArrayList<String> newCommands = new ArrayList<>(beforeCommands.size() + 1);
        newCommands.addAll(getExitOnErrorCommands());
        newCommands.addAll(beforeCommands);
        return newCommands;
    }

    protected List<String> getExitOnErrorCommands() {
        if (this.isWindowsTarget()) {
            return List.of("");
        }
        // errexit option may be unsupported by non-shell interpreter.
        return List.of("set -e");
    }

    // If targetOS is Windows OR targetOS is AUTO && current system is windows and we use process as a runner.(TLDR will run on windows)
    private boolean isWindowsTarget() {
        TargetOS os = this.getTargetOS();
        return os == TargetOS.WINDOWS ||
            (os == TargetOS.AUTO && SystemUtils.IS_OS_WINDOWS && this.getTaskRunner() instanceof Process);
    }
}
