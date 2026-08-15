package org.cses.flow.executor.commands;

import io.micronaut.json.JsonMapper;
import org.cses.flow.infrastructure.queues.entries.QueueMessageEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

final class CreateTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void restoresTheConcreteCreateCommandThroughTheExecutorContract() {
        Create command = new Create(
            "company-1",
            "flow-key-1",
            7,
            Map.of("amount", 1200.5)
        );

        QueueMessageEntry entry = QueueMessageEntry.create(
            "DISPATCH",
            ExecutionCommand.QUEUE_NAME,
            command
        );
        ExecutionCommand restored = entry.toEvent(ExecutionCommand.class);

        Create create = assertInstanceOf(Create.class, restored);
        assertEquals(ExecutionCommand.Type.CREATE, create.getType());
        assertEquals("company-1", create.getCompanyId());
        assertEquals("flow-key-1", create.getFlowKey());
        assertEquals(7L, create.getFlowVersion());
        assertEquals(Map.of("amount", 1200.5), create.getInputs());
        assertNull(create.dsl());
    }
}
