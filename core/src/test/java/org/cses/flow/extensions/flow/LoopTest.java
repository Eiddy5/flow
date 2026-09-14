package org.cses.flow.extensions.flow;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.extensions.log.Log;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoopTest {

    private Context plugins = builtInContext();

    @Test
    void materializesFixedIterationScopeFromRealFields() {
        Flow flow = plugins.deploy(
            "company-1",
            "flow-1",
            Map.of(
                "key", "fixed-loop",
                "tasks", List.of(Map.of(
                    "key", "repeat-three-times",
                    "type", Loop.class.getCanonicalName(),
                    "times", 3,
                    "tasks", List.of(Map.of(
                        "key", "work",
                        "type", Log.class.getCanonicalName(), "message", "test step"
                    ))
                ))
            ),
            null,
            ActorRef.create("user-1", "User"),
            1L
        );

        Loop loop = assertInstanceOf(Loop.class, flow.tasks().getFirst());
        assertEquals(3, loop.times());
        assertInstanceOf(Log.class, loop.tasks().getFirst());
    }

    @Test
    void rejectsAnEmptyBody() {
        assertThrows(RuntimeException.class, () ->
            plugins.modelValidator().validate(Loop.builder()
                .id("loop-id")
                .key("loop")
                .times(1)
                .build())
        );
    }
}
