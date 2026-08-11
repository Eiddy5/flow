package org.cses.flow.queues.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jooq.DSLContext;

/**
 * Message contract accepted by a Flow Queue.
 */
public interface Event {

    /**
     * Returns the business-defined key associated with this Event.
     */
    String key();

    /**
     * Returns the caller-owned transaction used by synchronous publishing,
     * or {@code null} when the Queue must open its own transaction.
     *
     * <p>The transaction context is runtime-only and is never part of the
     * persisted Event payload. Asynchronous publishing ignores it and always
     * opens a Queue-owned transaction.</p>
     */
    @JsonIgnore
    DSLContext dsl();
}
