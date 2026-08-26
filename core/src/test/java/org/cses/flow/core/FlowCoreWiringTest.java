package org.cses.flow.core;

import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.PluginRegistry;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.flow.Parallel;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowCoreWiringTest {

    @Test
    void micronautWiresPostgresCommandAndQueryChains() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            PluginRegistry registry = fixture.pluginRegistry();
            assertSame(
                AutomaticTask.class,
                registry.resolve(AutomaticTask.class.getName(), Task.class)
            );
            assertSame(
                Pause.class,
                registry.resolve(Pause.class.getName(), Task.class)
            );
            assertSame(
                Parallel.class,
                registry.resolve(Parallel.class.getName(), Task.class)
            );
            assertSame(
                Log.class,
                registry.resolve(Log.class.getName(), Task.class)
            );
            Session<User> session = fixture.sessionFor(
                "wiring-company"
            );

            var draft = service.save(
                session,
                PublishFlowCommand.from("""
                key: wiring-flow
                description: Micronaut 装配验证
                tasks:
                  - key: start
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                """)
            );
            service.save(
                session,
                PublishFlowCommand.from(draft.key(), false)
            );

            var current = service.flow(
                session,
                draft.key(),
                1L
            ).orElseThrow();
            assertTrue(!current.deleted());
            assertEquals(1L, current.reversion());
            assertEquals(
                AutomaticTask.class.getName(),
                current.tasks().getFirst().getType()
            );
            assertEquals(
                draft.source(),
                service.draft(session, draft.key()).orElseThrow().source()
            );
        }
    }

}
