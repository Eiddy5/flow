package org.cses.flow.core.services.executions;

import io.micronaut.context.annotation.Requires;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.VoidOutput;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only RunnableTask fixture for queue acceptance and blocking behavior.
 */
@Plugin
@Requires(property = "flow.test.workflow-task", value = "true")
@SuperBuilder
@NoArgsConstructor
public class TestWorkflowTask extends Task implements RunnableTask<VoidOutput> {

    private static AtomicInteger blockingRuns = new AtomicInteger();
    private static volatile CountDownLatch blockingStarted =
        new CountDownLatch(0);
    private static volatile CountDownLatch blockingReleased =
        new CountDownLatch(0);

    /**
     * Resets the controlled Worker invocation count and blocks its next run.
     */
    static void blockNextRun() {
        blockingRuns.set(0);
        blockingStarted = new CountDownLatch(1);
        blockingReleased = new CountDownLatch(1);
    }

    /**
     * Returns actual invocations since the controlled Worker was last armed.
     *
     * @return invocation count, including repeated calls for the same TaskRun
     */
    static int blockingRunCount() {
        return blockingRuns.get();
    }

    static boolean awaitBlockingRun() throws InterruptedException {
        return blockingStarted.await(10, TimeUnit.SECONDS);
    }

    static void releaseBlockingRun() {
        blockingReleased.countDown();
    }

    /**
     * 记录目标步骤的调用次数，并等待测试释放后返回空成功结果。
     *
     * @param context 运行上下文；本夹具不读取它
     * @return 释放等待后创建的空输出
     */
    @Override
    public VoidOutput run(RunContext context) {
        if ("target-block".equals(key())) {
            blockingRuns.incrementAndGet();
            blockingStarted.countDown();
            awaitRelease();
        }
        return VoidOutput.from();
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
