package org.cses.flow.extensions.flow;

import org.cses.flow.core.domains.conditions.ConditionContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTest {

    @Test
    void keepsTheRawRouteUntilAConditionIsRequested() {
        Route route = Route.builder()
            .id("route-id")
            .key("approval-route")
            .route("{{ outputs.prepare.result }} ==")
            .build();

        assertEquals("{{ outputs.prepare.result }} ==", route.route());
        assertThrows(IllegalArgumentException.class, route::condition);
    }

    @Test
    void parsesTheRouteWhenMatchingAConditionContext() {
        Route route = Route.builder()
            .id("route-id")
            .key("approval-route")
            .route("{{ inputs.level }} == A")
            .build();

        assertTrue(route.matches(ConditionContext.from(Map.of(
            "inputs",
            Map.of("level", "A")
        ))));
        assertFalse(route.matches(ConditionContext.from(Map.of(
            "inputs",
            Map.of("level", "B")
        ))));
    }
}
