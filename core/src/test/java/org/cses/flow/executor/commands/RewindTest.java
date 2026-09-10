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

class RewindTest {

    /** 初始化实际 PAAS Schema 所需的 JSON 绑定组件。 */
    @BeforeAll
    static void initializeJsonMapper() {
        PulsarTestEnvironment.initializeJson();
    }

    /** 命令往返保留一次分配的新实例身份和退回坐标。 */
    @Test
    void carriesOnlyTheSelectedHistoricalFragmentThroughTheQueueContract() {
        Rewind command = Rewind.from(
                session(),
                "execution-1",
                "pause-run-2",
                "prepare-run-1",
                "退回补充资料"
        );

        JacksonSchema<ExecutionCommand> schema = new JacksonSchema<>(ExecutionCommand.class);
        byte[] encoded = schema.encode(command);
        JsonObject payload = JsonObject.Parse(new String(encoded, StandardCharsets.UTF_8));

        assertEquals("execution-1", payload.getString(
                "executionId"
        ));
        assertEquals("pause-run-2", payload.getString(
                "sourceTaskRunId"
        ));
        assertEquals("prepare-run-1", payload.getString(
                "targetTaskRunId"
        ));
        assertEquals("退回补充资料", payload.getString(
                "reason"
        ));
        assertFalse(payload.has("flowId"));
        assertFalse(payload.has("flowReversion"));
        assertFalse(payload.has("dsl"));

        ExecutionCommand restored = schema.decode(encoded);
        Rewind restoredRewind = assertInstanceOf(Rewind.class, restored);
        assertEquals(command.getReplayExecutionId(), payload.getString("replayExecutionId"));
        org.junit.jupiter.api.Assertions.assertNotEquals(command.getExecutionId(), command.getReplayExecutionId());
        assertEquals(command, restoredRewind);
        assertEquals(ExecutionCommand.Type.REWIND, restoredRewind.getType());
        assertEquals("company-1", restoredRewind.getCompanyId());
        assertEquals("actor-1", restoredRewind.getActorId());
    }

    private static Session<User> session() {
        User user = new User();
        user.setId("actor-1");
        user.setName("Rewind Actor");
        user.setCompanyId("company-1");
        Session<User> session = new Session<>();
        session.setId("session-1");
        session.setUser(user);
        session.setCompanyId("company-1");
        return session;
    }
}
