package org.cses.flow.executor.commands;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.infrastructure.queues.entries.QueueMessageEntry;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.session.Session;
import org.paas.session.User;

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
        Session<User> session = session();
        Execution execution = Execution.create(
            "execution-1",
            "company-1",
            "flow-1",
            7,
            Map.of("amount", 1200.5)
        );
        Create command = Create.from(session, execution)
            .inTransaction(DSL.using(SQLDialect.POSTGRES));

        QueueMessageEntry entry = QueueMessageEntry.create(
            "DISPATCH",
            ExecutionCommand.QUEUE_NAME,
            command
        );
        ExecutionCommand restored = entry.toEvent(ExecutionCommand.class);

        Create create = assertInstanceOf(Create.class, restored);
        assertEquals(ExecutionCommand.Type.CREATE, create.getType());
        assertEquals(execution.id(), create.getExecutionId());
        assertEquals(execution.companyId(), create.getCompanyId());
        assertEquals(execution.flowId(), create.getFlowId());
        assertEquals(execution.flowReversion(), create.getFlowReversion());
        assertEquals("actor-1", create.getActorId());
        assertEquals(Map.of("amount", 1200.5), create.getInputs());
        assertNull(create.dsl());
    }

    private static Session<User> session() {
        User user = new User();
        user.setId("actor-1");
        user.setName("Executor Actor");
        user.setCompanyId("company-1");
        Session<User> session = new Session<>();
        session.setId("session-1");
        session.setUser(user);
        return session;
    }
}
