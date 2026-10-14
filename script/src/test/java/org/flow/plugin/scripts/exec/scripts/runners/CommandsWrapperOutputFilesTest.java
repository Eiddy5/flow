package org.flow.plugin.scripts.exec.scripts.runners;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.RunContext;
import org.flow.core.storages.StorageInterface;
import org.flow.core.tenant.TenantService;
import org.flow.core.utils.TestsUtils;
import org.flow.plugin.core.runner.Process;
import org.flow.plugin.scripts.exec.scripts.models.ScriptOutput;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class CommandsWrapperOutputFilesTest {

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
    void shouldResolveOutputFilesPebbleExpressionInScript() throws Exception {
        // Given
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, TASK, ImmutableMap.of());

        // Use Property.ofExpression to simulate YAML deserialization (value=null → Pebble rendering runs).
        // Dot-notation {{ outputFiles.outfile }} avoids nested quote escaping in the JSON expression.
        // When — script writes the resolved Pebble path to 'outfile' so the content proves resolution.
        ScriptOutput run = new CommandsWrapper(runContext)
            .withTaskRunner(Process.instance())
            .withOutputFiles(List.of("outfile"))
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withCommands(Property.<List<String>> ofExpression("[\"printf '%s' {{ outputFiles.outfile }} > outfile\"]"))
            .run();

        // Then
        assertThat(run.getExitCode()).isEqualTo(0);
        assertThat(run.getOutputFiles()).containsKey("outfile");
        String content = new String(storageInterface.get(TenantService.MAIN_TENANT, null, run.getOutputFiles().get("outfile")).readAllBytes());
        // The file content should be the resolved absolute path ending with /outfile
        assertThat(content).endsWith("/outfile");
    }

    @Test
    void shouldStillCollectGlobOutputFilesAfterExecution() throws Exception {
        // Given — glob patterns are NOT injected into Pebble context but are still collected post-run
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, TASK, ImmutableMap.of());

        // When
        ScriptOutput run = new CommandsWrapper(runContext)
            .withTaskRunner(Process.instance())
            .withOutputFiles(List.of("*.txt"))
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withCommands(Property.ofValue(List.of("echo hello > result.txt")))
            .run();

        // Then — glob still collected even though it was not in the Pebble context
        assertThat(run.getExitCode()).isEqualTo(0);
        assertThat(run.getOutputFiles()).containsKey("result.txt");
    }
}
