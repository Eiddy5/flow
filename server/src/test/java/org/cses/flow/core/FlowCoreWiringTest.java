package org.cses.flow.core;

import io.micronaut.context.ApplicationContext;
import org.cses.flow.core.plugins.PluginRegistry;
import org.cses.flow.core.plugins.RegisteredTaskTypeDispatcher;
import org.cses.flow.core.plugins.TaskExtension;
import org.cses.flow.core.plugins.TaskTypeDispatcher;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.extensions.tasks.AutomaticTaskPlugin;
import org.cses.flow.extensions.tasks.ParallelTaskPlugin;
import org.cses.flow.extensions.tasks.PauseTaskPlugin;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowCoreWiringTest {

    @Test
    void micronautWiresMemoryCommandAndQueryChains() {
        Map<String, Object> properties = Map.of(
            "flow.memory.enabled", true,
            "datasources.default.enabled", false,
            "flyway.datasources.default.enabled", false,
            "micronaut.config-client.enabled", false,
            "consul.client.registration.enabled", false,
            "grpc.server.enabled", false,
            "thrift.server.enabled", false
        );

        try (ApplicationContext context =
                 ApplicationContext.run(properties)) {
            FlowService service = context.getBean(FlowService.class);
            PluginRegistry registry = context.getBean(
                PluginRegistry.class
            );
            assertInstanceOf(
                RegisteredTaskTypeDispatcher.class,
                context.getBean(TaskTypeDispatcher.class)
            );
            assertInstanceOf(
                AutomaticTaskPlugin.class,
                registry.find(TaskExtension.class, "AUTO").orElseThrow()
            );
            assertInstanceOf(
                PauseTaskPlugin.class,
                registry.find(TaskExtension.class, "pause").orElseThrow()
            );
            assertInstanceOf(
                ParallelTaskPlugin.class,
                registry.find(
                    TaskExtension.class,
                    "parallel"
                ).orElseThrow()
            );
            Session<User> session = session("wiring-company");

            var draft = service.saveDraft(
                session,
                """
                key: wiring-flow
                description: Micronaut 装配验证
                tasks:
                  - key: start
                    type: AUTO
                """
            );
            service.deploy(
                session,
                draft.id()
            );

            var current = service.flow(
                session,
                draft.id(),
                1L
            ).orElseThrow();
            assertTrue(!current.isDeleted());
            assertEquals(1L, current.reversion());
            assertEquals("AUTO", current.tasks().getFirst().type());
            assertEquals(
                draft.raw(),
                service.draft(session, draft.id()).orElseThrow().raw()
            );
        }
    }

    private static Session<User> session(String companyId) {
        User user = new User();
        user.setId("wiring-user");
        user.setCompanyId(companyId);
        Session<User> session = new Session<>();
        session.setUser(user);
        return session;
    }
}
