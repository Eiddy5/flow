package org.cses.flow.core.runner;

import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RunContextTest {

    @Test
    void exposesTheExactTaskRunIdFromItsReservedVariable() {
        RunContext context = context(Map.of(
            RunContext.TASK_RUN_ID_VARIABLE,
            "task-run-1"
        ));

        assertEquals("task-run-1", context.taskRunId());
    }

    @Test
    void rejectsMissingOrInvalidTaskRunIdentityVariables() {
        assertThrows(
            IllegalStateException.class,
            () -> context(Map.of()).taskRunId()
        );
        assertThrows(
            IllegalStateException.class,
            () -> context(Map.of(
                RunContext.TASK_RUN_ID_VARIABLE,
                1
            )).taskRunId()
        );
        assertThrows(
            IllegalStateException.class,
            () -> context(Map.of(
                RunContext.TASK_RUN_ID_VARIABLE,
                " "
            )).taskRunId()
        );
    }

    @Test
    void exposesAnOptionalParentTaskRunIdentity() {
        assertTrue(context(Map.of()).parentTaskRunId().isEmpty());
        assertEquals(
            "parent-run-1",
            context(Map.of(
                RunContext.PARENT_TASK_RUN_ID_VARIABLE,
                "parent-run-1"
            )).parentTaskRunId().orElseThrow()
        );
        assertThrows(
            IllegalStateException.class,
            () -> context(Map.of(
                RunContext.PARENT_TASK_RUN_ID_VARIABLE,
                1
            )).parentTaskRunId()
        );
        assertThrows(
            IllegalStateException.class,
            () -> context(Map.of(
                RunContext.PARENT_TASK_RUN_ID_VARIABLE,
                " "
            )).parentTaskRunId()
        );
    }

    @Test
    void exposesImmutableFlowLevelVariables() {
        RunContext context = context(Map.of(
            RunContext.FLOW_VARIABLES_VARIABLE,
            Map.of("environment", "prod")
        ));

        assertEquals(
            Map.of("environment", "prod"),
            context.flowVariables()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> context.flowVariables().put("environment", "staging")
        );
    }

    @Test
    void separatesExecutionInputsFromTaskRunInputs() {
        RunContext context = context(Map.of(
            RunContext.INPUTS_VARIABLE,
            Map.of("amount", 1200),
            RunContext.TASK_INPUTS_VARIABLE,
            Map.of("outputs", Map.of("approved", true))
        ));

        assertEquals(Map.of("amount", 1200), context.inputs());
        assertEquals(
            Map.of("outputs", Map.of("approved", true)),
            context.taskInputs()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> context.inputs().put("amount", 1)
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> context.taskInputs().put("new", true)
        );
    }

    private static RunContext context(Map<String, ?> variables) {
        return RunContext.create(
            new Session<User>(),
            variables
        );
    }
}
