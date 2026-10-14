package org.flow.core.scheduler.events;

import java.time.Instant;

import org.flow.core.events.EventId;
import org.flow.core.models.triggers.TriggerEvaluationResult;
import org.flow.core.models.triggers.TriggerId;

import jakarta.annotation.Nullable;

/**
 * A trigger was evaluated.
 */
public record TriggerEvaluated(
    TriggerId id,
    @Nullable TriggerEvaluationResult evaluation,
    Instant timestamp,
    EventId eventId) implements TriggerEvent {

    public TriggerEvaluated(TriggerId id, @Nullable TriggerEvaluationResult evaluation) {
        this(id, evaluation, Instant.now(), EventId.create());
    }
}
