package org.cses.flow.executor.commands;

import io.micronaut.json.JsonMapper;
import org.cses.flow.infrastructure.queues.entries.QueueMessageEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class ResumeTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void carriesOnlyTheMinimalResumePayloadThroughTheQueueContract() {
        Resume command = Resume.from(
            session(),
            "execution-1",
            "task-run-1",
            Map.of("decision", "APPROVED")
        );

        QueueMessageEntry entry = QueueMessageEntry.create(
            "DISPATCH",
            ExecutionCommand.QUEUE_NAME,
            command
        );

        assertEquals("execution-1", entry.payloadJson().getString(
            "executionId"
        ));
        assertEquals("task-run-1", entry.payloadJson().getString(
            "taskRunId"
        ));
        assertFalse(entry.payloadJson().has("flowId"));
        assertFalse(entry.payloadJson().has("flowReversion"));
        assertFalse(entry.payloadJson().has("sessionId"));
        assertFalse(entry.payloadJson().has("device"));
        assertFalse(entry.payloadJson().has("dsl"));

        ExecutionCommand restored = entry.toEvent(ExecutionCommand.class);
        Resume restoredResume = assertInstanceOf(Resume.class, restored);
        assertEquals(ExecutionCommand.Type.RESUME, restoredResume.getType());
        assertEquals("company-1", restoredResume.getCompanyId());
        assertEquals("actor-1", restoredResume.getActorId());
        assertEquals(
            Map.of("decision", "APPROVED"),
            restoredResume.getOutputs()
        );
    }

    private static Session<User> session() {
        User user = new User();
        user.setId("actor-1");
        user.setName("Resume Actor");
        user.setCompanyId("company-1");
        Session<User> session = new Session<>();
        session.setId("session-1");
        session.setUser(user);
        session.setCompanyId("company-1");
        return session;
    }
}
