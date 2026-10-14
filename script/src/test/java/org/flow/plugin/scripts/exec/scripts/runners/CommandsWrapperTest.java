package org.flow.plugin.scripts.exec.scripts.runners;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.RunContext;
import org.flow.core.utils.TestsUtils;
import org.flow.plugin.core.runner.Process;
import org.flow.plugin.scripts.exec.scripts.models.ScriptOutput;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class CommandsWrapperTest {

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

    @Test
    void runsCommandsAndCapturesStandardOutput() throws Exception {
        // Given
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, TASK, ImmutableMap.of());
        CommandsWrapper wrapper = new CommandsWrapper(runContext)
            .withTaskRunner(Process.instance())
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withCommands(Property.ofValue(List.of("echo hello")));

        // When
        ScriptOutput run = wrapper.run();

        // Then
        assertThat(run.getExitCode()).isEqualTo(0);
        List<String> finalCommands = runContext.render(wrapper.getCommands()).asList(String.class);
        assertThat(finalCommands).containsExactly("/bin/sh", "-c", "echo hello");
        assertThat(run.getStdOutLineCount()).isEqualTo(1);
        assertThat(run.getStdErrLineCount()).isZero();
    }

}
