package org.cses.flow.core;

import io.micronaut.context.ApplicationContext;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.PluginRegistry;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.flow.Parallel;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
            "thrift.server.enabled", false,
            "pulsar.consumer.enabled", false,
            "jooq.send-event", false
        );

        try (ApplicationContext context =
                 ApplicationContext.run(properties)) {
            FlowService service = context.getBean(FlowService.class);
            PluginRegistry registry = context.getBean(
                PluginRegistry.class
            );
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
            Session<User> session = session("wiring-company");

            var draft = service.saveDraft(
                session,
                """
                key: wiring-flow
                description: Micronaut 装配验证
                tasks:
                  - key: start
                    type: org.cses.flow.extensions.tasks.AutomaticTask
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
            assertEquals(
                AutomaticTask.class.getName(),
                current.tasks().getFirst().getType()
            );
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
