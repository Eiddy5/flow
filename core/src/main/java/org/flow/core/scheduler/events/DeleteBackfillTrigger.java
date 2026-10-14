package org.flow.core.scheduler.events;

import java.time.Instant;

import org.flow.core.async.AsyncOperation;
import org.flow.core.events.EventId;
import org.flow.core.models.triggers.TriggerId;

import jakarta.annotation.Nullable;

/**
 * A command to delete a backfill.
 */
public record DeleteBackfillTrigger(
    TriggerId id,
    Instant timestamp,
    EventId eventId,
    @Nullable String operationId) implements TriggerEvent, AsyncOperation {

    public DeleteBackfillTrigger(TriggerId id) {
        this(id, Instant.now(), EventId.create(), null);
    }

    public DeleteBackfillTrigger withOperationId(String operationId) {
        return new DeleteBackfillTrigger(id, timestamp, eventId, operationId);
    }
}
