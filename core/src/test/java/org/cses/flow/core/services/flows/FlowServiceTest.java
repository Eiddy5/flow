package org.cses.flow.core.services.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowServiceTest {

    @Test
    void saveDraftCreatesOrUpdatesByCompanyAndFlowKey() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            Session<User> companyOne = fixture.sessionFor("draft-key-one");
            Session<User> companyTwo = fixture.sessionFor("draft-key-two");
            String raw = validYaml("unique-draft-key", "initial");

            FlowDraft first = service.saveDraft(
                companyOne,
                "unique-draft-key",
                raw
            );
            assertEquals("unique-draft-key", first.flowKey());
            assertNotEquals(first.id(), first.flowKey());

            FlowDraft revised = service.saveDraft(
                companyOne,
                "unique-draft-key",
                validYaml("unique-draft-key", "revised")
            );
            assertEquals(first.id(), revised.id());
            assertEquals(first.lockVersion() + 1, revised.lockVersion());

            FlowDraft otherCompany = service.saveDraft(
                companyTwo,
                "unique-draft-key",
                raw
            );
            assertNotEquals(first.id(), otherCompany.id());

            service.deleteDraft(companyOne, first.flowKey());
            assertThrows(
                WorkflowException.class,
                () -> service.saveDraft(
                    companyOne,
                    "unique-draft-key",
                    raw
                )
            );
        }
    }

    @Test
    void savesOpaqueInvalidDraftAndRejectsOnlyAtDeployment() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            Session<User> session = fixture.sessionFor("company-1");
            String invalidRaw = "key: [not-valid";

            FlowDraft draft = service.saveDraft(
                session,
                "invalid-draft",
                invalidRaw
            );
            assertEquals(
                invalidRaw,
                service.draft(session, draft.flowKey()).orElseThrow().raw()
            );

            assertThrows(
                RuntimeException.class,
                () -> service.deploy(session, draft.flowKey())
            );
            assertEquals(
                invalidRaw,
                service.draft(session, draft.flowKey()).orElseThrow().raw()
            );
            assertTrue(service.latestFlow(session, draft.flowKey()).isEmpty());
        }
    }

    @Test
    void deletedLatestReversionDoesNotFallBackToOlderFlow() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            Session<User> session = fixture.sessionFor("company-1");
            FlowDraft draft = service.saveDraft(
                session,
                validYaml("first")
            );
            Flow first = service.deploy(session, draft.flowKey());
            service.saveDraft(
                session,
                draft.flowKey(),
                validYaml("second")
            );
            Flow second = service.deploy(session, draft.flowKey());

            Flow deleted = service.delete(session, draft.flowKey());

            assertEquals(1L, first.reversion());
            assertEquals(2L, second.reversion());
            assertEquals(2L, deleted.reversion());
            assertTrue(deleted.isDeleted());
            assertTrue(service.latestFlow(session, draft.flowKey()).isEmpty());
            assertTrue(service.draft(session, draft.flowKey()).isEmpty());
            assertFalse(service.flow(
                session,
                draft.flowKey(),
                1L
            ).orElseThrow().isDeleted());
            assertTrue(service.flow(
                session,
                draft.flowKey(),
                2L
            ).orElseThrow().isDeleted());
            assertThrows(
                WorkflowException.class,
                () -> service.deploy(session, draft.flowKey())
            );
        }
    }

    @Test
    void prefersYamlKeysAndKeepsTheResolvedFlowKeyWhenLaterYamlOmitsIt() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            Session<User> session = fixture.session();
            FlowDraft draft = service.saveDraft(session, """
                key: externally-owned-flow
                tasks:
                  - key: externally-owned-task
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                """);

            Flow first = service.deploy(session, draft.flowKey());
            assertEquals("externally-owned-flow", first.key());
            assertEquals(
                "externally-owned-task",
                first.tasks().getFirst().key()
            );
            assertEquals(
                "externally-owned-flow",
                service.draft(session, draft.flowKey()).orElseThrow().flowKey()
            );
            assertEquals(
                draft.id(),
                service.draft(session, "externally-owned-flow")
                    .orElseThrow()
                    .id()
            );

            service.saveDraft(session, "externally-owned-flow", """
                description: key omitted on the next revision
                tasks:
                  - type: org.cses.flow.extensions.tasks.AutomaticTask
                """);
            Flow second = service.deploy(session, "externally-owned-flow");

            assertEquals("externally-owned-flow", second.key());
            assertEquals(2L, second.reversion());
        }
    }

    @Test
    void saveDraftKeepsTheExistingDraftIdentityAcrossReversions() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            Session<User> session = fixture.session();
            String yaml = """
                key: existing-flow-key
                tasks:
                  - key: start
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                """;

            Flow latest = fixture.deploy(yaml);
            FlowDraft original = service.draft(
                session,
                latest.key()
            ).orElseThrow();
            for (int version = 2; version <= 10; version++) {
                service.saveDraft(session, latest.key(), yaml);
                latest = service.deploy(session, latest.key());
            }
            FlowDraft saved = service.saveDraft(
                session,
                "existing-flow-key",
                yaml
            );
            assertEquals(original.id(), saved.id());
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

    private static String validYaml(String key, String description) {
        return """
            key: %s
            description: %s
            tasks:
              - key: start
                type: org.cses.flow.extensions.tasks.AutomaticTask
            """.formatted(key, description);
    }

}
