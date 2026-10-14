package org.flow.core.runners.pebble.functions;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.tasks.retrys.Exponential;
import org.flow.core.runners.ExecutionLogMetaStore;
import org.flow.core.runners.pebble.PebbleUtils;
import org.flow.core.utils.ListUtils;
import org.flow.core.utils.RetryUtils;

import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Singleton
public class ErrorLogsFunction implements FlowFunction {
    public static final String NAME = "errorLogs";
    @Inject
    private Provider<ExecutionLogMetaStore> executionLogMetaStore;

    @Inject
    private Provider<PebbleUtils> pebbleUtils;

    @Override
    public List<String> getArgumentNames() {
        return Collections.emptyList();
    }

    @Override
    public Map<String, String> getArgumentDefaults() {
        return Map.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) {
        if (!pebbleUtils.get().calledOnWorker()) {
            throw new PebbleException(null, "The 'errorLogs' function can only be used in the Worker as it access logs from the database.", lineNumber, self.getName());
        }

        Map<String, String> flow = (Map<String, String>) context.getVariable("flow");
        Map<String, String> execution = (Map<String, String>) context.getVariable("execution");

        RetryUtils.Instance<List<LogEntry>, Throwable> retry = RetryUtils.of(
            Exponential.builder()
                .delayFactor(2.0)
                .interval(Duration.ofMillis(100))
                .maxInterval(Duration.ofSeconds(1))
                .maxAttempts(-1)
                .maxDuration(Duration.ofSeconds(5))
                .build()
        );

        try {
            return retry.run(logs -> ListUtils.isEmpty(logs), () -> executionLogMetaStore.get().errorLogs(flow.get("tenantId"), execution.get("id")));
        } catch (RetryUtils.RetryFailed e) {
            return Collections.emptyList();
        } catch (Throwable e) {
            throw new PebbleException(e, "Unable to fetch error logs");
        }
    }
}
