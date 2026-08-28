package org.cses.flow.core.services.executions;

import io.micronaut.context.annotation.Requires;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic Task plugin used by UC-03.
 */
@Plugin
@Requires(property = "flow.uc03.auto-task", value = "true")
@SuperBuilder
@NoArgsConstructor
public final class Uc03AutomaticTask extends Task implements RunnableTask {

    private static volatile CountDownLatch blockingStarted =
        new CountDownLatch(0);
    private static volatile CountDownLatch blockingReleased =
        new CountDownLatch(0);

    static void blockNextRun() {
        blockingStarted = new CountDownLatch(1);
        blockingReleased = new CountDownLatch(1);
    }

    static boolean awaitBlockingRun() throws InterruptedException {
        return blockingStarted.await(10, TimeUnit.SECONDS);
    }

    static void releaseBlockingRun() {
        blockingReleased.countDown();
    }

    @Override
    public RunResult run(RunContext context) {
        return switch (key()) {
            case "prepare-input" -> RunResult.success(
                Map.of("payload", "prepared")
            );
            case "target-success" -> RunResult.success(Map.of(
                "result",
                "processed-" + nestedOutput(
                    context.taskInputs(),
                    "prepare-input",
                    "payload"
                )
            ));
            case "observe-output" -> RunResult.success(Map.of(
                "observed",
                nestedOutput(
                    context.taskInputs(),
                    "target-success",
                    "result"
                )
            ));
            case "target-fail" -> RunResult.failed(
                "uc03-explicit-failure"
            );
            case "target-throw" -> throw new IllegalStateException(
                "uc03-unhandled-failure"
            );
            case "target-block" -> {
                blockingStarted.countDown();
                awaitRelease();
                yield RunResult.success(Map.of());
            }
            default -> RunResult.success(Map.of());
        };
    }

    private static void awaitRelease() {
        try {
            blockingReleased.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Blocking UC Task was interrupted",
                exception
            );
        }
    }

    private static Object nestedOutput(
        Map<String, Object> inputs,
        String taskKey,
        String output
    ) {
        Object raw = inputs.get("outputs");
        if (!(raw instanceof Map<?, ?> byTask)
            || !(byTask.get(taskKey) instanceof Map<?, ?> outputs)
            || !outputs.containsKey(output)) {
            throw new IllegalStateException(
                "Missing expected Task input output: "
                    + taskKey + "." + output
            );
        }
        return outputs.get(output);
    }
}
