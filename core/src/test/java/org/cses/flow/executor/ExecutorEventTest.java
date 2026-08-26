package org.cses.flow.executor;

import io.micronaut.json.JsonMapper;
import org.cses.flow.infrastructure.queues.entries.QueueMessageEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class ExecutorEventTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void persistsOnlyLifecycleIdentityInTheQueuePayload() {
        ExecutorEvent event = ExecutorEvent.from(
            "execution-1",
            "company-1",
            ExecutorEvent.EventType.UPDATED
        );

        assertEquals("execution-1", event.executionId());
        assertEquals("company-1", event.companyId());
        assertEquals(ExecutorEvent.EventType.UPDATED, event.eventType());

        QueueMessageEntry entry = QueueMessageEntry.create(
            "DISPATCH",
            ExecutorEvent.QUEUE_NAME,
            event
        );

        assertEquals(
            "execution-1",
            entry.payloadJson().getString("executionId")
        );
        assertEquals(
            "company-1",
            entry.payloadJson().getString("companyId")
        );
        assertEquals(
            "UPDATED",
            entry.payloadJson().getString("eventType")
        );
        assertFalse(entry.payloadJson().has("actorId"));
        assertFalse(entry.payloadJson().has("flowId"));
        assertFalse(entry.payloadJson().has("taskRunId"));
        assertFalse(entry.payloadJson().has("outputs"));
        assertEquals(event, entry.toEvent(ExecutorEvent.class));
    }
}
