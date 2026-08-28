package org.cses.flow.core.services.executions;

import io.micronaut.context.annotation.Requires;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test-only RunnableTask fixture for queue acceptance and blocking behavior.
 */
@Plugin
@Requires(property = "flow.test.workflow-task", value = "true")
@SuperBuilder
@NoArgsConstructor
public class TestWorkflowTask extends Task implements RunnableTask {

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
        if ("target-block".equals(key())) {
            blockingStarted.countDown();
            awaitRelease();
        }
        return RunResult.success(Map.of());
    }

    private static void awaitRelease() {
        try {
            blockingReleased.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Blocking test task was interrupted",
                exception
            );
        }
    }
}
