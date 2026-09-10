package org.cses.flow.executor.commands;

import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonObject;
import org.paas.pulsar.JacksonSchema;
import org.paas.session.Session;
import org.paas.session.User;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CancelTest {

    /** 初始化实际 PAAS Schema 所需的 JSON 绑定组件。 */
    @BeforeAll
    static void initializeJsonMapper() {
        PulsarTestEnvironment.initializeJson();
    }

    /** 通过 PAAS Schema 往返取消命令，验证最小载荷及租户、操作者身份。 */
    @Test
    void carriesOnlyTheMinimalCancelPayloadThroughTheQueueContract() {
        Cancel command = Cancel.from(session(), "execution-1");

        JacksonSchema<ExecutionCommand> schema = new JacksonSchema<>(ExecutionCommand.class);
        byte[] encoded = schema.encode(command);
        JsonObject payload = JsonObject.Parse(new String(encoded, StandardCharsets.UTF_8));

        assertEquals("execution-1", payload.getString(
            "executionId"
        ));
        assertFalse(payload.has("flowId"));
        assertFalse(payload.has("flowReversion"));
        assertFalse(payload.has("sessionId"));
        assertFalse(payload.has("dsl"));

        ExecutionCommand restored = schema.decode(encoded);
        Cancel restoredCancel = assertInstanceOf(Cancel.class, restored);
        assertEquals(ExecutionCommand.Type.CANCEL, restoredCancel.getType());
        assertEquals("company-1", restoredCancel.getCompanyId());
        assertEquals("actor-1", restoredCancel.getActorId());
    }

    private static Session<User> session() {
        User user = new User();
        user.setId("actor-1");
        user.setName("Cancel Actor");
        user.setCompanyId("company-1");
        Session<User> session = new Session<>();
        session.setId("session-1");
        session.setUser(user);
        session.setCompanyId("company-1");
        return session;
    }
}
