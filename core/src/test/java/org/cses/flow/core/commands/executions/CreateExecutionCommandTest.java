package org.cses.flow.core.commands.executions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CreateExecutionCommandTest {

    @Test
    void acceptsAStableExecutionIdAndExactFlowReversion() {
        CreateExecutionCommand command = new CreateExecutionCommand(
            "execution-1",
            "flow-1",
            7,
            false
        );

        command.validate();

        assertEquals("execution-1", command.executionId());
        assertEquals("flow-1", command.flowId());
        assertEquals(7L, command.expectedFlowReversion());
        assertFalse(command.startImmediately());
    }

    @Test
    void rejectsInvalidRecoverableStartIdentity() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new CreateExecutionCommand(" ", "flow-1", 1, false)
                .validate()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new CreateExecutionCommand(
                "execution-1",
                "flow-1",
                0,
                false
            ).validate()
        );
    }
}
