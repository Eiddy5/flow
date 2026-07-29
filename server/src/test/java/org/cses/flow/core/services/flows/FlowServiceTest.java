package org.cses.flow.core.services.flows;

import io.micronaut.context.ApplicationContext;
import org.cses.flow.core.domains.flows.FlowWithSource;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowServiceTest {

    private static final Map<String, Object> PROPERTIES = Map.of(
        "flow.memory.enabled", true,
        "datasources.default.enabled", false,
        "flyway.datasources.default.enabled", false,
        "micronaut.config-client.enabled", false,
        "consul.client.registration.enabled", false,
        "grpc.server.enabled", false,
        "thrift.server.enabled", false
    );

    @Test
    void savesOpaqueInvalidSourceAndRejectsOnlyAtDeployment() {
        try (ApplicationContext context =
                 ApplicationContext.run(PROPERTIES)) {
            FlowService service = context.getBean(FlowService.class);
            Session<User> session = session("company-1");
            String invalidRaw = "key: [not-valid";

            FlowWithSource source = service.saveDraft(
                session,
                invalidRaw
            );
            assertEquals(
                invalidRaw,
                service.source(session, source.id()).orElseThrow().raw()
            );

            assertThrows(
                RuntimeException.class,
                () -> service.deploy(session, source.id())
            );
            assertEquals(
                invalidRaw,
                service.source(session, source.id()).orElseThrow().raw()
            );
            assertTrue(service.latestFlow(session, source.id()).isEmpty());
        }
    }

    private static Session<User> session(String companyId) {
        User user = new User();
        user.setId("user-1");
        user.setCompanyId(companyId);
        Session<User> session = new Session<>();
        session.setUser(user);
        return session;
    }
}
