package org.cses.flow.extensions.flow;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoopTest {

    private final Context plugins = builtInContext();

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
                        "type", AutomaticTask.class.getCanonicalName()
                    ))
                ))
            ),
            null,
            ActorRef.create("user-1", "User"),
            1L
        );

        Loop loop = assertInstanceOf(Loop.class, flow.tasks().getFirst());
        assertEquals(3, loop.times());
        assertEquals(3, loop.maxIterations());
        assertInstanceOf(AutomaticTask.class, loop.tasks().getFirst());
    }

    @Test
    void rejectsAnEmptyBodyAndANonDirectFirstChild() {
        assertThrows(RuntimeException.class, () ->
            plugins.modelValidator().validate(Loop.builder()
                .id("loop-id")
                .key("loop")
                .times(1)
                .build())
        );
        assertThrows(RuntimeException.class, () ->
            plugins.modelValidator().validate(Loop.builder()
                .id("loop-id")
                .key("loop")
                .times(1)
                .tasks(List.of(AutomaticTask.builder()
                    .id("work-id")
                    .key("work")
                    .route(org.cses.flow.core.domains.tasks.TaskRoute
                        .parse("outputs.status == \"READY\""))
                    .build()))
                .build())
        );
    }
}
