package org.flow.plugin.scripts.exec.scripts.runners;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.WorkingDir;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.storages.StorageInterface;
import org.flow.core.tenant.TenantService;
import org.flow.core.utils.TestsUtils;
import org.flow.plugin.core.runner.Process;
import org.flow.plugin.scripts.exec.scripts.models.ScriptOutput;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class CommandsWrapperExecutionContextTest {

    private static final Task TASK = new Task() {
        @Override
        public String getId() {
            return "test";
        }

        @Override
        public String getType() {
            return "test";
        }
    };

    @Inject
    private TestRunContextFactory runContextFactory;

    @Inject
    private StorageInterface storageInterface;

    @Test
    void shouldWriteTheExecutionContextInTheWorkingDirectory() throws Exception {
        // Given
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, TASK, ImmutableMap.of());

        // When — the script copies the context file out so its content can be asserted on
        ScriptOutput run = new CommandsWrapper(runContext)
            .withTaskRunner(Process.instance())
            .withExecutionContext(true)
            .withOutputFiles(List.of("context.json"))
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withCommands(Property.ofValue(List.of("cp " + WorkingDir.EXECUTION_CONTEXT_FILE_NAME + " context.json")))
            .run();

        // Then
        assertThat(run.getExitCode()).isEqualTo(0);

        Map<String, Object> context = JacksonMapper.ofJson().readValue(
            storageInterface.get(TenantService.MAIN_TENANT, null, run.getOutputFiles().get("context.json")),
            JacksonMapper.MAP_TYPE_REFERENCE
        );

        assertThat(context).containsKeys("flow", "execution", "task", "taskrun");
        // envs and globals are already handed to the script as environment variables,
        // addSecretConsumer is internal plumbing that has no business in a metadata file
        assertThat(context).doesNotContainKeys("envs", "globals", "addSecretConsumer");
    }

    @Test
    void shouldNeverCollectTheExecutionContextAsAnOutputFile() throws Exception {
        // Given — a plain '*' glob, which Java's PathMatcher matches against leading dots too
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, TASK, ImmutableMap.of());

        // When
        ScriptOutput run = new CommandsWrapper(runContext)
            .withTaskRunner(Process.instance())
            .withExecutionContext(true)
            .withOutputFiles(List.of("*"))
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withCommands(Property.ofValue(List.of("echo hello > out.txt")))
            .run();

        // Then — the context file holds decrypted secrets, it must never reach internal storage
        assertThat(run.getExitCode()).isEqualTo(0);
        assertThat(run.getOutputFiles()).containsKey("out.txt");
        assertThat(run.getOutputFiles()).doesNotContainKey(WorkingDir.EXECUTION_CONTEXT_FILE_NAME);
    }

    @Test
    void shouldRemoveTheExecutionContextOnceTheCommandsAreDone() throws Exception {
        // Given — a working directory shared by both runs, as a WorkingDirectory flowable does
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, TASK, ImmutableMap.of());

        new CommandsWrapper(runContext)
            .withTaskRunner(Process.instance())
            .withExecutionContext(true)
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withCommands(Property.ofValue(List.of("test -f " + WorkingDir.EXECUTION_CONTEXT_FILE_NAME)))
            .run();

        // When — a second task in the same working directory that never opted in
        ScriptOutput second = new CommandsWrapper(runContext)
            .withTaskRunner(Process.instance())
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withCommands(Property.ofValue(List.of("test ! -f " + WorkingDir.EXECUTION_CONTEXT_FILE_NAME)))
            .run();

        // Then — it must not inherit the previous task's context
        assertThat(second.getExitCode()).isEqualTo(0);
    }

    @Test
    void shouldNotWriteTheExecutionContextByDefault() throws Exception {
        // Given
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, TASK, ImmutableMap.of());

        // When
        ScriptOutput run = new CommandsWrapper(runContext)
            .withTaskRunner(Process.instance())
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withCommands(Property.ofValue(List.of("test ! -f " + WorkingDir.EXECUTION_CONTEXT_FILE_NAME)))
            .run();

        // Then — the script itself asserts the file is absent
        assertThat(run.getExitCode()).isEqualTo(0);
    }
}
