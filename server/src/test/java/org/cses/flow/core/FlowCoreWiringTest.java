package org.cses.flow.core;

import io.micronaut.context.ApplicationContext;
import org.cses.flow.core.domains.flows.FlowStatus;
import org.cses.flow.core.domains.tasks.TaskTypeDispatcher;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.extensions.tasks.AutomaticTaskPlugin;
import org.cses.flow.extensions.tasks.PauseTaskPlugin;
import org.cses.flow.extensions.tasks.RegisteredTaskTypeDispatcher;
import org.cses.flow.extensions.tasks.TaskPluginRegistry;
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
            TaskPluginRegistry registry = context.getBean(
                TaskPluginRegistry.class
            );
            assertInstanceOf(
                RegisteredTaskTypeDispatcher.class,
                context.getBean(TaskTypeDispatcher.class)
            );
            assertInstanceOf(
                AutomaticTaskPlugin.class,
                registry.find("AUTO").orElseThrow()
            );
            assertInstanceOf(
                PauseTaskPlugin.class,
                registry.find("pause").orElseThrow()
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
            service.publish(
                session,
                draft.id()
            );

            var current = service.flow(
                session,
                draft.id(),
                1L,
                FlowStatus.DEPLOYED
            ).orElseThrow();
            assertEquals(FlowStatus.DEPLOYED, current.status());
            assertEquals(1L, current.reversion());
            assertEquals("AUTO", current.tasks().getFirst().type());
            assertTrue(service.flow(
                session,
                draft.id(),
                null,
                FlowStatus.DRAFT
            ).isEmpty());
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
