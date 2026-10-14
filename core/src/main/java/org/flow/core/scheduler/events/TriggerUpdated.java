package org.flow.core.scheduler.events;

import java.time.Instant;

import org.flow.core.events.EventId;
import org.flow.core.models.triggers.TriggerId;

/**
 * An existing trigger was updated.
 */
public record TriggerUpdated(
    TriggerId id,
    int revision,
    Instant timestamp,
    EventId eventId) implements TriggerEvent {
    public TriggerUpdated(TriggerId id, int revision) {
        this(id, revision, Instant.now(), EventId.create());
    }
}
