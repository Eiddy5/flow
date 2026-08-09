package org.cses.flow.core.plugins;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;

import java.util.Map;

/**
 * Test plugin proving that one concrete Task owns its custom definition field.
 */
@Plugin(
    title = "Notification",
    description = "Sends one notification to a configured channel."
)
@SuperBuilder
@NoArgsConstructor
public final class TestNotificationTask
    extends Task implements RunnableTask {

    @NotBlank
    @Schema(
        title = "Channel",
        description = "Destination channel name.",
        example = "flow-alerts"
    )
    private String channel;

    public String channel() {
        return channel;
    }

    @Override
    public RunResult run(RunContext context) {
        return RunResult.success(Map.of("channel", channel));
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return channel;
    }
}
