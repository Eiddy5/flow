package org.cses.flow.core.commands.executions;

import org.cses.flow.core.services.executions.commands.CreateExecutionCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CreateExecutionCommandTest {

    @Test
    void acceptsAStableExecutionIdAndExactFlowVersion() {
        CreateExecutionCommand command = CreateExecutionCommand.from(
            "execution-1",
            "flow-1",
            7
        );

        command.validate();

        assertEquals("execution-1", command.executionId());
        assertEquals("flow-1", command.flowKey());
        assertEquals(7L, command.expectedFlowVersion());
    }

    @Test
    void rejectsInvalidRecoverableStartIdentity() {
        assertThrows(
            IllegalArgumentException.class,
            () -> CreateExecutionCommand.from(" ", "flow-1", 1)
                .validate()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> CreateExecutionCommand.from(
                "execution-1",
                "flow-1",
                0
            ).validate()
        );
    }
}
