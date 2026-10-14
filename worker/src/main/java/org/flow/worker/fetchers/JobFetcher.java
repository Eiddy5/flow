package org.flow.worker.fetchers;

import java.time.Duration;

import org.flow.core.worker.models.WorkerContext;

/**
 * Abstraction for sourcing {@code WorkerJob}s into a worker's in-memory queue.
 * <p>
 * Implementations claim jobs from a backing queue and account for local capacity.
 * <p>
 * Implementations must be {@link Runnable} so they can be submitted to the
 * worker's IO thread executor.
 */
public interface JobFetcher extends Runnable, AutoCloseable {

    /**
     * Initialize the fetcher and bind it to the given {@link WorkerContext}.
     * <p>
     * Must be called before the fetcher loop is started.
     */
    void init(WorkerContext context);

    /** Release local capacity after execution has ended. */
    void onJobCompleted(String jobId);

    /** Release control subscriptions after the worker finishes shutdown. */
    @Override
    void close();

    /**
     * Pause job intake. Any in-flight jobs continue running.
     */
    void pause();

    /**
     * Resume job intake after a {@link #pause()}.
     */
    void resume();

    /**
     * Stop the fetcher and release any resources, waiting up to {@code timeout}
     * for in-flight work to complete.
     *
     * @param timeout the maximum time to wait. {@link Duration#ZERO} means
     *        signal stop and return immediately.
     */
    void stop(Duration timeout);
}
