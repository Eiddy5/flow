package org.cses.flow.core.services.flows;

import io.micronaut.context.ApplicationContext;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        "thrift.server.enabled", false,
        "pulsar.consumer.enabled", false,
        "jooq.send-event", false
    );

    @Test
    void savesOpaqueInvalidDraftAndRejectsOnlyAtDeployment() {
        try (ApplicationContext context =
                 ApplicationContext.run(PROPERTIES)) {
            FlowService service = context.getBean(FlowService.class);
            Session<User> session = session("company-1");
            String invalidRaw = "key: [not-valid";

            FlowDraft draft = service.saveDraft(
                session,
                invalidRaw
            );
            assertEquals(
                invalidRaw,
                service.draft(session, draft.id()).orElseThrow().raw()
            );

            assertThrows(
                RuntimeException.class,
                () -> service.deploy(session, draft.id())
            );
            assertEquals(
                invalidRaw,
                service.draft(session, draft.id()).orElseThrow().raw()
            );
            assertTrue(service.latestFlow(session, draft.id()).isEmpty());
        }
    }

    @Test
    void deletedLatestReversionDoesNotFallBackToOlderFlow() {
        try (ApplicationContext context =
                 ApplicationContext.run(PROPERTIES)) {
            FlowService service = context.getBean(FlowService.class);
            Session<User> session = session("company-1");
            FlowDraft draft = service.saveDraft(
                session,
                validYaml("first")
            );
            Flow first = service.deploy(session, draft.id());
            service.saveDraft(
                session,
                draft.id(),
                validYaml("second")
            );
            Flow second = service.deploy(session, draft.id());

            Flow deleted = service.delete(session, draft.id());

            assertEquals(1L, first.reversion());
            assertEquals(2L, second.reversion());
            assertEquals(2L, deleted.reversion());
            assertTrue(deleted.isDeleted());
            assertTrue(service.latestFlow(session, draft.id()).isEmpty());
            assertTrue(service.draft(session, draft.id()).isEmpty());
            assertFalse(service.flow(
                session,
                draft.id(),
                1L
            ).orElseThrow().isDeleted());
            assertTrue(service.flow(
                session,
                draft.id(),
                2L
            ).orElseThrow().isDeleted());
            assertThrows(
                WorkflowException.class,
                () -> service.deploy(session, draft.id())
            );
        }
    }

    private static String validYaml(String description) {
        return """
            key: latest-deletion-flow
            description: %s
            tasks:
              - key: start
                type: org.cses.flow.extensions.tasks.AutomaticTask
            """.formatted(description);
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
