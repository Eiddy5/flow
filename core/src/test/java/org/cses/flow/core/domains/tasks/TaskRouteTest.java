package org.cses.flow.core.domains.tasks;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRouteTest {

    @Test
    void supportsDirectAndOneOutputExpressScenarios() {
        TaskRoute direct = TaskRoute.parse(" ");
        TaskRoute conditional = TaskRoute.parse(
            "outputs.decision == \"APPROVED\""
        );

        assertEquals(TaskRoute.direct(), direct);
        assertEquals("DIRECT", direct.source());
        assertTrue(direct.matches(null));
        assertEquals(
            "decision",
            conditional.referencedOutputKey().orElseThrow()
        );
        assertTrue(conditional.matches(Map.of(
            "decision",
            "APPROVED"
        )));
        assertFalse(conditional.matches(Map.of(
            "decision",
            "REJECTED"
        )));
        assertThrows(
            IllegalArgumentException.class,
            () -> TaskRoute.parse(
                "outputs.check.status == \"DONE\""
            )
        );
    }

    @Test
    void matchesFlowInputsWithTypedComparison() {
        TaskRoute route = TaskRoute.parse("inputs.amount > 1000");

        assertEquals("amount", route.referencedInputKey().orElseThrow());
        assertTrue(route.matches(Map.of(), Map.of("amount", 1200)));
        assertFalse(route.matches(Map.of(), Map.of("amount", 800)));
    }

    @Test
    void matchesFlowLevelVariables() {
        TaskRoute route = TaskRoute.parse(
            "variables.environment == \"prod\""
        );

        assertEquals(
            "environment",
            route.referencedVariableKey().orElseThrow()
        );
        assertTrue(route.matches(
            Map.of(),
            Map.of(),
            Map.of("environment", "prod")
        ));
        assertFalse(route.matches(
            Map.of(),
            Map.of(),
            Map.of("environment", "staging")
        ));
    }
}
