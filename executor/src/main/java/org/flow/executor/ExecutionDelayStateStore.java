package org.flow.executor;

import java.time.Instant;
import java.util.function.Consumer;

import org.flow.core.runners.ExecutionDelay;

/**
 * This state store is used by the {@link org.flow.core.runners.Executor} to handle execution delays (Pause, retries, LoopUntil, ...).
 */
public interface ExecutionDelayStateStore {
    /**
     * Process expired execution delays using the provided consumer
     * This method is used periodically by the {@link org.flow.core.runners.Executor} to process expired execution delays.
     *
     * @implNote Implementors must use some sort of transaction (FOR UPDATE SKIP LOCKED or {@link org.flow.core.lock.LockService#tryLock(String, String, Runnable)}) for accuracy.
     */
    void processExpired(Instant now, Consumer<ExecutionDelay> consumer);

    /**
     * Save an execution delay.
     */
    void save(ExecutionDelay executionDelay);
}
