package org.flow.core.scheduler.events;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.flow.core.async.AsyncOperation;
import org.flow.core.events.EventId;
import org.flow.core.models.Label;
import org.flow.core.models.triggers.TriggerId;
import org.flow.core.serializers.ListOrMapOfLabelDeserializer;
import org.flow.core.serializers.ListOrMapOfLabelSerializer;
import org.flow.core.validations.NoSystemLabelValidation;

import jakarta.annotation.Nullable;

/**
 * A command to backfill a trigger.
 */
public record CreateBackfillTrigger(
    TriggerId id,
    Backfill backfill,
    Instant timestamp,
    EventId eventId,
    @Nullable String operationId) implements TriggerEvent, AsyncOperation {

    public CreateBackfillTrigger(TriggerId id, Backfill backfill) {
        this(id, backfill, Instant.now(), EventId.create(), null);
    }

    public CreateBackfillTrigger withOperationId(String operationId) {
        return new CreateBackfillTrigger(id, backfill, timestamp, eventId, operationId);
    }

    public record Backfill(
        ZonedDateTime start,
        ZonedDateTime end,
        Map<String, Object> inputs,
        @JsonSerialize(using = ListOrMapOfLabelSerializer.class)
        @JsonDeserialize(using = ListOrMapOfLabelDeserializer.class) List<@NoSystemLabelValidation Label> labels) {
    }
}
