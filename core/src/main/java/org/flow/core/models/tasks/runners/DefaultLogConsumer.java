package org.flow.core.models.tasks.runners;

import java.time.Instant;

import org.flow.core.runners.RunContext;

/**
 * Default implementation of an @{link {@link AbstractLogConsumer}}
 */
public class DefaultLogConsumer extends AbstractLogConsumer {
    private static final String LOG_DEBUG_MARKER = "##flow:log:debug##";
    private static final String LOG_INFO_MARKER = "##flow:log:info##";

    private final RunContext runContext;
    private volatile boolean debugMode = false;

    public DefaultLogConsumer(RunContext runContext) {
        this.runContext = runContext;
    }

    @Override
    public void accept(String line, Boolean isStdErr) {
        this.accept(line, isStdErr, null);
    }

    public void accept(String line, Boolean isStdErr, Instant instant) {
        if (LOG_DEBUG_MARKER.equals(line)) {
            debugMode = true;
            return;
        }
        if (LOG_INFO_MARKER.equals(line)) {
            debugMode = false;
            return;
        }
        outputs.putAll(PluginUtilsService.parseOut(line, runContext.logger(), runContext, isStdErr, instant, debugMode));

        if (isStdErr) {
            this.stdErrCount.incrementAndGet();
        } else {
            this.stdOutCount.incrementAndGet();
        }
    }
}
