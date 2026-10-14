package org.flow.core.scheduler.events;

import java.time.Instant;

import org.flow.core.events.EventId;
import org.flow.core.models.triggers.TriggerId;

/**
 * The worker holding a trigger is gone (crashed or terminated) without reporting a result.
 * <p>
 * The scheduler reacts by releasing the trigger's lock so it can be resubmitted from the
 * current flow definition — or left alone if it is disabled or deleted.
 */
public record TriggerWorkerLost(
    TriggerId id,
    String workerUid,
    long dispatchEpoch,
    Instant timestamp,
    EventId eventId) implements TriggerEvent {

    public TriggerWorkerLost(TriggerId id, String workerUid, long dispatchEpoch) {
        this(id, workerUid, dispatchEpoch, Instant.now(), EventId.create());
    }
}
