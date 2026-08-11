package org.cses.flow.infrastructure.queues.entries;

import org.cses.flow.queues.event.Event;
import org.flow.gen.flow.pojos.QueuesObject;
import org.jooq.JSONB;
import org.paas.common.util.StringUtil;
import org.paas.json.JsonObject;

import java.util.Objects;

/**
 * Persistence entry for one message in the shared Queue table.
 */
public final class QueueMessageEntry extends QueuesObject {

    public static QueueMessageEntry create(
        String queueType,
        String queueName,
        Event event
    ) {
        Objects.requireNonNull(event, "event");
        JsonObject payload = JsonObject.From(event);
        payload.remove("dsl");

        QueueMessageEntry entry = new QueueMessageEntry();
        entry.id = StringUtil.newId();
        entry.queueType = Objects.requireNonNull(
            queueType,
            "queueType"
        );
        entry.queueName = Objects.requireNonNull(queueName, "queueName");
        entry.eventKey = event.key();
        entry.payload = JSONB.valueOf(
            payload.toJson()
        );
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
