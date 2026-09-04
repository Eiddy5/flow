package org.cses.flow.core.services.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.services.flows.commands.DeleteFlowCommand;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.Test;
import org.paas.session.RecordState;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowServiceTest {

    @Test
    void exposesOneSaveMethodAndUsesTheCommandDraftState() {
        assertEquals(
            1L,
            Arrays.stream(FlowService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("save"))
                .count()
        );
        assertFalse(Arrays.stream(FlowService.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().equals("saveDraft")));
        assertFalse(Arrays.stream(FlowService.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().equals("deploy")));

        PublishFlowCommand defaultDraft = PublishFlowCommand.from(
            null,
            "key: default-draft",
            null
        );
        assertTrue(defaultDraft.draft());
        assertFalse(PublishFlowCommand.from("deployed-flow", false).draft());
        assertTrue(DeleteFlowCommand.from("default-delete").draft());
        assertFalse(DeleteFlowCommand.from("deployed-delete", false).draft());
        assertEquals(
            1L,
            Arrays.stream(FlowService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("delete"))
                .count()
        );
        assertFalse(Arrays.stream(FlowService.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().equals("deleteDraft")));
    }

    /**
     * Verifies that draft saves append tenant-scoped versions with distinct
     * database row identities.
     */
    @Test
    void saveDraftAppendsByCompanyAndFlowKey() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            Session<User> companyOne = fixture.sessionFor("draft-key-one");
            Session<User> companyTwo = fixture.sessionFor("draft-key-two");
            String raw = validYaml("unique-draft-key", "initial");

            Flow first = service.save(
                companyOne,
                PublishFlowCommand.from("unique-draft-key", raw)
            );
            assertEquals("unique-draft-key", first.key());
            assertNotEquals(first.id(), first.key());
            assertTrue(first.draft());
            assertFalse(first.deployed());
            assertEquals(1L, first.version());
            assertEquals("initial", first.description());
            assertFalse(first.tasks().isEmpty());
            assertEquals(raw, first.source());

            Flow revised = service.save(
                companyOne,
                PublishFlowCommand.from(
                    "unique-draft-key",
                    validYaml("unique-draft-key", "revised")
                )
            );
            assertNotEquals(first.id(), revised.id());
            assertEquals(2L, revised.version());

            Flow otherCompany = service.save(
                companyTwo,
                PublishFlowCommand.from("unique-draft-key", raw)
            );
            assertNotEquals(first.id(), otherCompany.id());
            assertEquals(1L, otherCompany.version());

            Flow deleted = service.delete(
                companyOne,
                first.key(),
                true
            );
            Flow replacement = service.save(
                companyOne,
                PublishFlowCommand.from("unique-draft-key", raw)
            );
            assertEquals(3L, deleted.version());
            assertEquals(4L, replacement.version());
        }
    }

    /**
     * Saves a draft whose raw source declares tenant and audit status, then
     * verifies both returned and queried Flow facts still come from the
     * Session and domain initialization.
     */
    @Test
    void companyAndStatusComeFromTheDomainInsteadOfDraftSource() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            Session<User> session = fixture.sessionFor("domain-company");
            String raw = """
                companyId: source-company
                key: domain-owned-fields
                status: Delete
                tasks:
                  - key: start
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                """;

            Flow saved = service.save(
                session,
                PublishFlowCommand.from("domain-owned-fields", raw)
            );
            Flow queried = service.draft(
                session,
                saved.key()
            ).orElseThrow();

            assertEquals(session.getCompanyId(), saved.companyId());
            assertEquals(RecordState.Open, saved.status());
            assertEquals(session.getCompanyId(), queried.companyId());
            assertEquals(RecordState.Open, queried.status());
            assertEquals(raw, queried.source());
        }
    }

    @Test
    void savesDraftWithInvalidTasksAndRejectsOnlyAtDeployment() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            Session<User> session = fixture.sessionFor("company-1");
            String invalidRaw = """
                key: invalid-draft
                tasks:
                  - key: unresolved
                    type: missing.Plugin
                """;

            Flow draft = service.save(
                session,
                PublishFlowCommand.from("invalid-draft", invalidRaw)
            );
            assertEquals(
                invalidRaw,
                service.draft(session, draft.key()).orElseThrow().source()
            );

            assertThrows(
                RuntimeException.class,
                () -> service.save(
                    session,
                    PublishFlowCommand.from(draft.key(), false)
                )
            );
            assertEquals(
                invalidRaw,
                service.draft(session, draft.key()).orElseThrow().source()
            );
            assertTrue(service.latestFlow(session, draft.key()).isEmpty());
        }
    }

    /**
     * Verifies that a deleted latest deployed version is not replaced by an
     * older active result when draft saves share the version sequence.
     */
    @Test
    void deletedLatestReversionDoesNotFallBackToOlderFlow() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            Session<User> session = fixture.sessionFor("company-1");
            Flow draft = service.save(
                session,
                PublishFlowCommand.from(validYaml("first"))
            );
            Flow first = service.save(
                session,
                PublishFlowCommand.from(draft.key(), false)
            );
            assertFalse(first.draft());
            assertTrue(first.deployed());
            assertEquals(draft.source(), first.source());
            assertFalse(first.tasks().isEmpty());
            service.save(
                session,
                PublishFlowCommand.from(draft.key(), validYaml("second"))
            );
            Flow second = service.save(
                session,
                PublishFlowCommand.from(draft.key(), false)
            );

            Flow deleted = service.delete(session, draft.key(), false);

            assertEquals(2L, first.reversion());
            assertEquals(4L, second.reversion());
            assertEquals(5L, deleted.reversion());
            assertTrue(deleted.deleted());
            assertTrue(service.latestFlow(session, draft.key()).isEmpty());
            assertTrue(service.draft(session, draft.key()).isPresent());
            assertTrue(service.flow(
                session,
                draft.key(),
                1L
            ).isEmpty());
            assertFalse(service.flow(
                session,
                draft.key(),
                2L
            ).orElseThrow().deleted());
            assertTrue(service.flow(
                session,
                draft.key(),
                5L
            ).orElseThrow().deleted());
            Flow deletedDraft = service.delete(session, draft.key(), true);
            assertTrue(deletedDraft.draft());
            assertTrue(deletedDraft.deleted());
            assertTrue(service.draft(session, draft.key()).isEmpty());
            assertThrows(
                WorkflowException.class,
                () -> service.save(
                    session,
                    PublishFlowCommand.from(draft.key(), false)
                )
            );
        }
    }

    /**
     * Verifies Flow key resolution while draft and deployed saves consume
     * successive Repository-assigned versions.
     */
    @Test
    void prefersYamlKeysAndKeepsTheResolvedFlowKeyWhenLaterYamlOmitsIt() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            Session<User> session = fixture.session();
            Flow draft = service.save(
                session,
                PublishFlowCommand.from("""
                key: externally-owned-flow
                tasks:
                  - key: externally-owned-task
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                """)
            );

            Flow first = service.save(
                session,
                PublishFlowCommand.from(draft.key(), false)
            );
            assertEquals("externally-owned-flow", first.key());
            assertEquals(
                "externally-owned-task",
                first.tasks().getFirst().key()
            );
            assertEquals(
                "externally-owned-flow",
                service.draft(session, draft.key()).orElseThrow().key()
            );
            assertEquals(
                draft.id(),
                service.draft(session, "externally-owned-flow")
                    .orElseThrow()
                    .id()
            );

            service.save(
                session,
                PublishFlowCommand.from("externally-owned-flow", """
                description: key omitted on the next revision
                tasks:
                  - type: org.cses.flow.extensions.log.Log
                    message: "test step"
                """)
            );
            Flow second = service.save(
                session,
                PublishFlowCommand.from("externally-owned-flow", false)
            );

            assertEquals("externally-owned-flow", second.key());
            assertEquals(4L, second.reversion());
        }
    }

    /**
     * Verifies that draft and deployed saves share one increasing version
     * sequence while every save receives a new row identity.
     */
    @Test
    void savesUseNewRowIdentityAndOneSharedVersionSequence() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowService service = fixture.flowService();
            Session<User> session = fixture.session();
            String yaml = """
                key: existing-flow-key
                tasks:
                  - key: start
                    type: org.cses.flow.extensions.log.Log
                    message: "test step"
                """;

            Flow deployed = fixture.deploy(yaml);
            Flow original = service.draft(
                session,
                deployed.key()
            ).orElseThrow();
            Flow saved = service.save(
                session,
                PublishFlowCommand.from("existing-flow-key", yaml)
            );
            Flow nextDeployed = service.save(
                session,
                PublishFlowCommand.from(saved.key(), false)
            );

            assertEquals(1L, original.version());
            assertEquals(2L, deployed.version());
            assertEquals(3L, saved.version());
            assertEquals(4L, nextDeployed.version());
            assertNotEquals(original.id(), saved.id());
            assertNotEquals(saved.id(), nextDeployed.id());
        }
    }

    private static String validYaml(String description) {
        return """
            key: latest-deletion-flow
            description: %s
            tasks:
              - key: start
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """.formatted(description);
    }

    private static String validYaml(String key, String description) {
        return """
            key: %s
            description: %s
            tasks:
              - key: start
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """.formatted(key, description);
    }

}
