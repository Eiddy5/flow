package org.cses.flow.queues;

/**
 * Lifecycle handle for one Consumer registration.
 *
 * <p>Lifecycle operations are thread-safe and idempotent. Calls to the
 * registered Consumer never overlap within one subscription.</p>
 */
public interface QueueSubscription extends AutoCloseable {

    /**
     * Pauses future delivery without interrupting an active Consumer call.
     */
    void pause();

    /**
     * Returns whether this subscription is paused.
     */
    boolean isPaused();

    /**
     * Resumes future delivery after a pause.
     */
    void resume();

    /**
     * Returns whether this subscription is registered and not closed.
     */
    boolean isActive();

    /**
     * Stops future delivery and waits for an active Consumer call to finish.
     * When called by that Consumer itself, closing finishes after the current
     * call returns instead of waiting on itself.
     */
    @Override
    void close();
}
