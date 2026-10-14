package org.flow.core.scheduler.events;

import java.time.Instant;

import org.flow.core.events.EventId;
import org.flow.core.models.triggers.TriggerId;

/**
 * A trigger was received by a worker.
 */
public record TriggerReceived(
    TriggerId id,
    String workerId,
    Instant timestamp,
    EventId eventId) implements TriggerEvent {

    public TriggerReceived(TriggerId id, String workerId) {
        this(id, workerId, Instant.now(), EventId.create());
    }
}
