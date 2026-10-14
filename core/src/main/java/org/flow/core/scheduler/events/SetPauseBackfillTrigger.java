package org.flow.core.scheduler.events;

import java.time.Instant;

import org.flow.core.async.AsyncOperation;
import org.flow.core.events.EventId;
import org.flow.core.models.triggers.TriggerId;

import jakarta.annotation.Nullable;

/**
 * A command to pause or resume a backfill.
 */
public record SetPauseBackfillTrigger(
    TriggerId id,
    boolean pause,
    Instant timestamp,
    EventId eventId,
    @Nullable String operationId) implements TriggerEvent, AsyncOperation {

    public SetPauseBackfillTrigger(TriggerId id, boolean pause) {
        this(id, pause, Instant.now(), EventId.create(), null);
    }

    public SetPauseBackfillTrigger withOperationId(String operationId) {
        return new SetPauseBackfillTrigger(id, pause, timestamp, eventId, operationId);
    }
}
