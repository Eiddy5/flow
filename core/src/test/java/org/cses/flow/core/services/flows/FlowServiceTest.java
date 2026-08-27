package org.cses.flow.core.services.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.services.flows.commands.DeleteFlowCommand;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void saveDraftCreatesOrUpdatesByCompanyAndFlowKey() {
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
            assertNull(first.versionOrNull());
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
            assertEquals(first.id(), revised.id());

            Flow otherCompany = service.save(
                companyTwo,
                PublishFlowCommand.from("unique-draft-key", raw)
            );
            assertNotEquals(first.id(), otherCompany.id());

            service.delete(companyOne, first.key(), true);
            assertThrows(
                WorkflowException.class,
                () -> service.save(
                    companyOne,
                    PublishFlowCommand.from("unique-draft-key", raw)
                )
            );
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

            assertEquals(1L, first.reversion());
            assertEquals(2L, second.reversion());
            assertEquals(2L, deleted.reversion());
            assertTrue(deleted.deleted());
            assertTrue(service.latestFlow(session, draft.key()).isEmpty());
            assertTrue(service.draft(session, draft.key()).isPresent());
            assertFalse(service.flow(
                session,
                draft.key(),
                1L
            ).orElseThrow().deleted());
            assertTrue(service.flow(
                session,
                draft.key(),
                2L
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
                    type: org.cses.flow.extensions.tasks.AutomaticTask
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
                  - type: org.cses.flow.extensions.tasks.AutomaticTask
                """)
            );
            Flow second = service.save(
                session,
                PublishFlowCommand.from("externally-owned-flow", false)
            );

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
            Flow original = service.draft(
                session,
                latest.key()
            ).orElseThrow();
            for (int version = 2; version <= 10; version++) {
                service.save(
                    session,
                    PublishFlowCommand.from(latest.key(), yaml)
                );
                latest = service.save(
                    session,
                    PublishFlowCommand.from(latest.key(), false)
                );
            }
            Flow saved = service.save(
                session,
                PublishFlowCommand.from("existing-flow-key", yaml)
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
