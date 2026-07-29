package org.cses.flow.core.services.executions;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.workers.DefaultTaskHandler;
import org.cses.flow.worker.WorkerContext;
import org.cses.flow.worker.WorkerTaskHandler;
import org.cses.flow.worker.WorkerTaskResult;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

/**
 * Deterministic AUTO execution adapter for UC-03. It replaces only the AUTO
 * worker implementation; Flow materialization, execution state, persistence,
 * dispatch and public Service paths remain production implementations.
 */
@Singleton
@Requires(property = "flow.uc03.auto-handler", value = "true")
@Replaces(DefaultTaskHandler.class)
final class Uc03AutomaticTaskHandler implements WorkerTaskHandler {

    @Override
    public boolean supports(Task task) {
        return task instanceof AutomaticTask;
    }

    @Override
    public <S extends Session<U>, U extends User> WorkerTaskResult execute(
        WorkerContext<S, U> context
    ) {
        String key = context.workerTask().task().key();
        return switch (key) {
            case "prepare-input" -> WorkerTaskResult.completed(
                context.workerTask(),
                Map.of("payload", "prepared")
            );
            case "target-success" -> {
                Object payload = nestedOutput(
                    context.workerTask().inputs(),
                    "payload"
                );
                yield WorkerTaskResult.completed(
                    context.workerTask(),
                    Map.of("result", "processed-" + payload)
                );
            }
            case "observe-output" -> {
                Object result = nestedOutput(
                    context.workerTask().inputs(),
                    "result"
                );
                yield WorkerTaskResult.completed(
                    context.workerTask(),
                    Map.of("observed", result)
                );
            }
            case "target-fail" -> WorkerTaskResult.failed(
                context.workerTask(),
                "uc03-explicit-failure"
            );
            case "target-throw" ->
                throw new IllegalStateException("uc03-unhandled-failure");
            default -> WorkerTaskResult.completed(
                context.workerTask(),
                Map.of()
            );
        };
    }

    private static Object nestedOutput(
        Map<String, Object> inputs,
        String output
    ) {
        Object raw = inputs.get("outputs");
        if (!(raw instanceof Map<?, ?> outputs)
            || !outputs.containsKey(output)) {
            throw new IllegalStateException(
                "Missing expected AUTO input output: " + output
            );
        }
        return outputs.get(output);
    }
}
