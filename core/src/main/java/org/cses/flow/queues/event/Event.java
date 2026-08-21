package org.cses.flow.queues.event;

/**
 * Message contract accepted by a Flow Queue.
 */
public interface Event {

    /**
     * Returns the business-defined key associated with this Event.
     */
    String key();
}
