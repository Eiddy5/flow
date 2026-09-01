package org.cses.flow.executor.commands;

import io.micronaut.json.JsonMapper;
import org.cses.flow.infrastructure.queues.entries.QueueMessageEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.session.Session;
import org.paas.session.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class RewindTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void carriesOnlyTheSelectedHistoricalFragmentThroughTheQueueContract() {
        Rewind command = Rewind.from(
                session(),
                "execution-1",
                "pause-run-2",
                "prepare-run-1",
                "退回补充资料"
        );

        QueueMessageEntry entry = QueueMessageEntry.create(
                "DISPATCH",
                ExecutionCommand.QUEUE_NAME,
                command
        );

        assertEquals("execution-1", entry.payloadJson().getString(
                "executionId"
        ));
        assertEquals("pause-run-2", entry.payloadJson().getString(
                "sourceTaskRunId"
        ));
        assertEquals("prepare-run-1", entry.payloadJson().getString(
                "targetTaskRunId"
        ));
        assertEquals("退回补充资料", entry.payloadJson().getString(
                "reason"
        ));
        assertFalse(entry.payloadJson().has("flowId"));
        assertFalse(entry.payloadJson().has("flowReversion"));
        assertFalse(entry.payloadJson().has("dsl"));

        ExecutionCommand restored = entry.toEvent(ExecutionCommand.class);
        Rewind restoredRewind = assertInstanceOf(Rewind.class, restored);
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
