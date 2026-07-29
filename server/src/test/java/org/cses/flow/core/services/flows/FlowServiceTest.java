package org.cses.flow.core.services.flows;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void requiresIdWhenEditingAnExistingFlowKey() {
        try (ApplicationContext context =
                 ApplicationContext.run(PROPERTIES)) {
            FlowService service = context.getBean(FlowService.class);
            Session<User> session = session("company-1");
            service.saveDraft(
                session,
                yaml("existing-key", "初始 Flow", "start")
            );

            assertThrows(
                IllegalArgumentException.class,
                () -> service.saveDraft(
                    session,
                    yaml("existing-key", "已修改 Flow", "start", "finish")
                )
            );
        }
    }

    private static String yaml(
        String key,
        String description,
        String... taskKeys
    ) {
        String tasks = java.util.Arrays.stream(taskKeys)
            .map(taskKey -> """
                  - key: %s
                    type: AUTO
                """.formatted(taskKey))
            .reduce("", String::concat);
        return """
            key: %s
            description: %s
            tasks:
            %s
            """.formatted(key, description, tasks.indent(2));
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
