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

final class CancelTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void carriesOnlyTheMinimalCancelPayloadThroughTheQueueContract() {
        Cancel command = Cancel.from(session(), "execution-1");

        QueueMessageEntry entry = QueueMessageEntry.create(
            "DISPATCH",
            ExecutionCommand.QUEUE_NAME,
            command
        );

        assertEquals("execution-1", entry.payloadJson().getString(
            "executionId"
        ));
        assertFalse(entry.payloadJson().has("flowId"));
        assertFalse(entry.payloadJson().has("flowReversion"));
        assertFalse(entry.payloadJson().has("sessionId"));
        assertFalse(entry.payloadJson().has("dsl"));

        ExecutionCommand restored = entry.toEvent(ExecutionCommand.class);
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
