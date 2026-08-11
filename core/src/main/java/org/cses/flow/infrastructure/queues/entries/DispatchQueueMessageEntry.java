package org.cses.flow.infrastructure.queues.entries;

import org.cses.flow.queues.event.Event;
import org.flow.gen.flow.pojos.DispatchQueueMessagesObject;
import org.flow.gen.flow.records.DispatchQueueMessagesRecord;
import org.jooq.JSONB;
import org.paas.common.util.StringUtil;
import org.paas.json.JsonObject;

import java.util.Objects;

/**
 * Persistence entry for one pending Default Dispatch Queue message.
 */
public final class DispatchQueueMessageEntry
    extends DispatchQueueMessagesObject {

    public static DispatchQueueMessageEntry create(
        String queueName,
        Event event
    ) {
        Objects.requireNonNull(event, "event");
        JsonObject payload = JsonObject.From(event);
        payload.remove("dsl");

        DispatchQueueMessageEntry entry = new DispatchQueueMessageEntry();
        entry.id = StringUtil.newId();
        entry.queueName = Objects.requireNonNull(queueName, "queueName");
        entry.eventKey = event.key();
        entry.payload = JSONB.valueOf(
            payload.toJson()
        );
        return entry;
    }

    public static DispatchQueueMessageEntry fromRecord(
        DispatchQueueMessagesRecord record
    ) {
        Objects.requireNonNull(record, "record");
        DispatchQueueMessageEntry entry = new DispatchQueueMessageEntry();
        entry.id = record.getId();
        entry.queueName = record.getQueueName();
        entry.eventKey = record.getEventKey();
        entry.payload = record.getPayload();
        entry.createdAt = record.getCreatedAt();
        return entry;
    }

    public JsonObject payloadJson() {
        if (payload == null) {
            return null;
        }
        return JsonObject.Parse(payload.data());
    }

    public <T> T toEvent(Class<T> eventType) {
        Objects.requireNonNull(eventType, "eventType");
        JsonObject payload = payloadJson();
        return payload == null ? null : payload.asObject(eventType);
    }
}
