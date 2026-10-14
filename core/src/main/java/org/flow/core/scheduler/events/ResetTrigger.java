package org.flow.core.scheduler.events;

import java.time.Instant;

import org.flow.core.async.AsyncOperation;
import org.flow.core.events.EventId;
import org.flow.core.models.triggers.TriggerId;

import jakarta.annotation.Nullable;

/**
 * A command to reset a trigger.
 */
public record ResetTrigger(
    TriggerId id,
    Instant timestamp,
    EventId eventId,
    @Nullable String operationId) implements TriggerEvent, AsyncOperation {

    public ResetTrigger(TriggerId id) {
        this(id, Instant.now(), EventId.create(), null);
    }

    public ResetTrigger withOperationId(String operationId) {
        return new ResetTrigger(id, timestamp, eventId, operationId);
    }
}
