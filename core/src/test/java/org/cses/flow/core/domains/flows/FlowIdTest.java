package org.cses.flow.core.domains.flows;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowIdTest {

    @Test
    void createsLogicalFlowSelectorWithoutVersion() {
        FlowId flowId = FlowId.from(" company ", " flow ");

        assertEquals("company", flowId.companyId());
        assertEquals("flow", flowId.key());
        assertNull(flowId.version());
    }

    @Test
    void createsExactFlowSelectorWithPositiveVersion() {
        FlowId flowId = FlowId.from("company", "flow", 3);

        assertEquals("company", flowId.companyId());
        assertEquals("flow", flowId.key());
        assertEquals(3L, flowId.version());
    }

    @Test
    void rejectsInvalidSelectorValues() {
        assertThrows(
            IllegalArgumentException.class,
            () -> FlowId.from(" ", "flow")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FlowId.from("company", "flow", 0)
        );
    }
}
