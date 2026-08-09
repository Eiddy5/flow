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
}
