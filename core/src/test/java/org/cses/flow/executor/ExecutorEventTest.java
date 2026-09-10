package org.cses.flow.executor;

import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonObject;
import org.paas.pulsar.JacksonSchema;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExecutorEventTest {

    /** 初始化实际 PAAS Schema 所需的 JSON 绑定组件。 */
    @BeforeAll
    static void initializeJsonMapper() {
        PulsarTestEnvironment.initializeJson();
    }

    /** 通过 PAAS Schema 往返执行事件，验证仅传输生命周期身份与事件类型。 */
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

        JacksonSchema<ExecutorEvent> schema = new JacksonSchema<>(ExecutorEvent.class);
        byte[] encoded = schema.encode(event);
        JsonObject payload = JsonObject.Parse(new String(encoded, StandardCharsets.UTF_8));

        assertEquals(
            "execution-1",
            payload.getString("executionId")
        );
        assertEquals(
            "company-1",
            payload.getString("companyId")
        );
        assertEquals(
            "UPDATED",
            payload.getString("eventType")
        );
        assertFalse(payload.has("actorId"));
        assertFalse(payload.has("flowId"));
        assertFalse(payload.has("taskRunId"));
        assertFalse(payload.has("outputs"));
        assertEquals(event, schema.decode(encoded));
    }
}
