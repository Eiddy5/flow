package org.cses.flow.core.services.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-01 Flow 草稿生命周期与多租户管理.md
 */
class Uc01FlowLifecycleTest {

    private final List<WorkflowUcFixture> fixtures = new ArrayList<>();

    @AfterEach
    void closeFixtures() {
        fixtures.forEach(WorkflowUcFixture::close);
    }

    /**
     * Saves, revises, and deletes one draft while checking each returned
     * version and database-row identifier through {@link FlowService}.
     */
    @Test
    void s1UserCompletesTheDraftLifecycle() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String initial = validYaml("uc01-s1", "initial");
        String revised = validYaml("uc01-s1", "revised");

        Flow created = saveDraft(service, user, initial);
        assertFalse(created.id().isBlank());
        assertEquals(1, created.version());
        assertFalse(created.deleted());
        Flow queriedCreated = service.draft(
            user,
            created.key()
        ).orElseThrow();
        assertEquals(created.id(), queriedCreated.id());
        assertEquals(created.version(), queriedCreated.version());
        assertEquals(initial, queriedCreated.source());

        Flow edited = saveDraft(service,
            user,
            created.key(),
            revised
        );
        assertNotEquals(created.id(), edited.id());
        assertEquals(2, edited.version());
        Flow queriedEdited = service.draft(
            user,
            created.key()
        ).orElseThrow();
        assertEquals(edited.id(), queriedEdited.id());
        assertEquals(edited.version(), queriedEdited.version());
        assertEquals(revised, queriedEdited.source());
        assertTrue(service.latestFlow(user, created.key()).isEmpty());

        Flow deleted = service.delete(user, created.key(), true);
        assertNotEquals(edited.id(), deleted.id());
        assertEquals(3, deleted.version());
        assertTrue(deleted.deleted());
        assertTrue(service.draft(user, created.key()).isEmpty());
    }

    /**
     * Saves an invalid draft, observes a failed deployment without a new
     * version, then revises and deletes the draft through {@link FlowService}.
     */
    @Test
    void s2UserRecoversFromAnInvalidDeployment() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String invalid = """
            key: uc01-invalid
            tasks:
              - key: unresolved
                type: missing.Plugin
            """;

        Flow draft = saveDraft(service, user, "uc01-invalid", invalid);
        assertEquals(1, draft.version());
        assertEquals(
            invalid,
            service.draft(user, draft.key()).orElseThrow().source()
        );
        assertThrows(
            RuntimeException.class,
            () -> deploy(service, user, draft.key())
        );
        assertEquals(
            invalid,
            service.draft(user, draft.key()).orElseThrow().source()
        );
        assertEquals(
            draft.version(),
            service.draft(user, draft.key()).orElseThrow().version()
        );
        assertTrue(service.latestFlow(user, draft.key()).isEmpty());

        Flow revised = saveDraft(service, user, draft.key(), """
            key: uc01-invalid
            tasks:
              - key: still-unresolved
                type: missing.AnotherPlugin
            """);
        assertNotEquals(draft.id(), revised.id());
        assertEquals(2, revised.version());
        Flow deleted = service.delete(user, draft.key(), true);
        assertEquals(3, deleted.version());
        assertTrue(service.draft(user, draft.key()).isEmpty());
        assertTrue(service.latestFlow(user, draft.key()).isEmpty());
    }

    /**
     * Saves the same Flow key in two tenants and checks that each tenant sees
     * only its own first version before independently deleting it.
     */
    @Test
    void s3TenantsManageIdenticalDraftsIndependently() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> tenantA = fixture.session();
        Session<User> tenantB = fixture.sessionFor("tenant-b");
        String sameRaw = validYaml("uc01-s3", "same");

        Flow draftA = saveDraft(service, tenantA, sameRaw);
        Flow draftB = saveDraft(service, tenantB, sameRaw);
        assertNotEquals(draftA.id(), draftB.id());
        assertEquals(1, draftA.version());
        assertEquals(1, draftB.version());
        assertEquals(
            draftA.id(),
            service.draft(tenantA, draftB.key()).orElseThrow().id()
        );
        assertEquals(
            draftB.id(),
            service.draft(tenantB, draftA.key()).orElseThrow().id()
        );
        assertEquals(
            1,
            service.draft(tenantB, draftB.key()).orElseThrow().version()
        );
        assertEquals(
            sameRaw,
            service.draft(tenantB, draftB.key()).orElseThrow().source()
        );
        assertEquals(
            1,
            service.draft(tenantA, draftA.key()).orElseThrow().version()
        );

        Flow deletedA = service.delete(tenantA, draftA.key(), true);
        assertEquals(2, deletedA.version());
        assertTrue(service.draft(tenantA, draftA.key()).isEmpty());
        assertSameVisibleDraft(
            draftB,
            service.draft(tenantB, draftB.key()).orElseThrow()
        );
        Flow deletedB = service.delete(tenantB, draftB.key(), true);
        assertEquals(2, deletedB.version());
        assertTrue(service.draft(tenantB, draftB.key()).isEmpty());
    }

    /**
     * Revises a draft from another user in the same tenant and checks that
     * creation audit facts remain while update facts and version advance.
     */
    @Test
    void s4UsersInOneTenantPreserveCreationAudit() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        String companyId = fixture.session().getCompanyId();
        Session<User> creator = session(
            companyId,
            "creator-user",
            "Creator"
        );
        Session<User> editor = session(
            companyId,
            "editor-user",
            "Editor"
        );

        Flow created = saveDraft(service,
            creator,
            validYaml("uc01-s4", "created")
        );
        Flow seenByEditor = service.draft(
            editor,
            created.key()
        ).orElseThrow();
        Flow savedByEditor = saveDraft(service,
            editor,
            created.key(),
            validYaml("uc01-s4", "edited")
        );
        Flow edited = service.draft(
            creator,
            created.key()
        ).orElseThrow();

        assertEquals(1, created.version());
        assertEquals(2, savedByEditor.version());
        assertNotEquals(created.id(), savedByEditor.id());
        assertEquals(savedByEditor.id(), edited.id());
        assertEquals(savedByEditor.version(), edited.version());
        assertEquals("creator-user", edited.creator().id());
        assertEquals(created.createdAt(), edited.createdAt());
        assertEquals("editor-user", edited.updater().id());
        assertTrue(edited.updatedAt() >= seenByEditor.updatedAt());
        assertTrue(edited.source().contains("edited"));

        Flow deleted = service.delete(creator, created.key(), true);
        assertEquals(3, deleted.version());
        assertTrue(service.draft(creator, created.key()).isEmpty());
    }

    /**
     * Queries missing and invalid Flow keys and checks that the saved control
     * draft remains unchanged.
     */
    @Test
    void s5WrongFlowKeysDoNotAffectTheControlDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        Flow control = saveDraft(service,
            user,
            validYaml("uc01-s5", "control")
        );
        Flow before = service.draft(
            user,
            control.key()
        ).orElseThrow();

        assertTrue(service.draft(user, "missing-draft-key").isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () -> service.draft(user, " ")
        );
        assertEquals(
            before,
            service.draft(user, control.key()).orElseThrow()
        );
        assertEquals(1, before.version());

        Flow deleted = service.delete(user, control.key(), true);
        assertEquals(2, deleted.version());
        assertTrue(service.draft(user, control.key()).isEmpty());
    }

    /**
     * Attempts every draft management operation without a tenant and checks
     * that no new visible version or mutation is produced.
     */
    @Test
    void s6MissingTenantIdentityCannotManageDrafts() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> noTenant = session(null, "no-tenant", "No tenant");
        Flow control = saveDraft(service,
            owner,
            validYaml("uc01-s6", "control")
        );
        Flow before = service.draft(
            owner,
            control.key()
        ).orElseThrow();
        assertEquals(1, before.version());

        assertThrows(
            IllegalArgumentException.class,
            () -> saveDraft(
                service,
                noTenant,
                validYaml("uc01-s6-new", "no tenant")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.draft(noTenant, control.key())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> saveDraft(service, noTenant, control.key(), "changed")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.delete(noTenant, control.key(), true)
        );
        assertEquals(
            before,
            service.draft(owner, control.key()).orElseThrow()
        );
        assertTrue(service.latestFlow(owner, control.key()).isEmpty());
        assertTrue(service.draft(owner, "uc01-s6-new").isEmpty());

        Flow deleted = service.delete(owner, control.key(), true);
        assertEquals(2, deleted.version());
        assertTrue(service.draft(owner, control.key()).isEmpty());
    }

    /**
     * Queries one tenant's draft key from another tenant and checks that only
     * the owner can observe its content, version, and audit facts.
     */
    @Test
    void s7AnotherTenantCannotQueryTheDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        Flow draft = saveDraft(service,
            owner,
            validYaml("uc01-s7", "owner")
        );
        Flow before = service.draft(
            owner,
            draft.key()
        ).orElseThrow();

        assertTrue(service.draft(other, draft.key()).isEmpty());
        assertEquals(
            before,
            service.draft(owner, draft.key()).orElseThrow()
        );
        Flow deleted = service.delete(owner, draft.key(), true);
        assertEquals(2, deleted.version());
        assertTrue(service.draft(owner, draft.key()).isEmpty());
    }

    /**
     * Revises the same Flow key independently in two tenants and checks that
     * each latest draft retains only its tenant's content and audit facts.
     */
    @Test
    void s8TenantsReviseTheSameFlowKeyIndependently() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> tenantA = fixture.session();
        Session<User> tenantB = fixture.sessionFor("tenant-b");
        Flow draftA = saveDraft(service,
            tenantA,
            validYaml("uc01-s8", "tenant-a-initial")
        );
        Flow draftB = saveDraft(service,
            tenantB,
            validYaml("uc01-s8", "tenant-b-initial")
        );
        Flow beforeA = service.draft(
            tenantA,
            draftA.key()
        ).orElseThrow();
        Flow beforeB = service.draft(
            tenantB,
            draftB.key()
        ).orElseThrow();
        assertEquals(1, beforeA.version());
        assertEquals(1, beforeB.version());

        Flow revisedA = saveDraft(service,
            tenantA,
            draftA.key(),
            validYaml("uc01-s8", "tenant-a-revised")
        );
        assertEquals(2, revisedA.version());
        assertNotEquals(draftA.id(), revisedA.id());
        assertSameVisibleDraft(
            beforeB,
            service.draft(tenantB, draftB.key()).orElseThrow()
        );

        Flow revisedB = saveDraft(service,
            tenantB,
            draftB.key(),
            validYaml("uc01-s8", "tenant-b-revised")
        );
        assertEquals(2, revisedB.version());
        assertNotEquals(draftB.id(), revisedB.id());
        assertSameVisibleDraft(
            revisedA,
            service.draft(tenantA, draftA.key()).orElseThrow()
        );
        assertSameVisibleDraft(
            revisedB,
            service.draft(tenantB, draftB.key()).orElseThrow()
        );

        assertEquals(
            3,
            service.delete(tenantA, draftA.key(), true).version()
        );
        assertTrue(service.draft(tenantA, draftA.key()).isEmpty());
        assertEquals(
            3,
            service.delete(tenantB, draftB.key(), true).version()
        );
        assertTrue(service.draft(tenantB, draftB.key()).isEmpty());
    }

    /**
     * Attempts a cross-tenant delete, then checks that the owner can append
     * the next draft version and complete deletion.
     */
    @Test
    void s9AnotherTenantCannotDeleteTheDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        Flow draft = saveDraft(service,
            owner,
            validYaml("uc01-s9", "owner")
        );

        assertThrows(
            WorkflowException.class,
            () -> service.delete(other, draft.key(), true)
        );
        assertSameVisibleDraft(
            draft,
            service.draft(owner, draft.key()).orElseThrow()
        );
        Flow revised = saveDraft(service,
            owner,
            draft.key(),
            validYaml("uc01-s9", "owner-edited")
        );
        assertNotEquals(draft.id(), revised.id());
        assertEquals(2, revised.version());
        assertTrue(
            service.draft(owner, draft.key())
                .orElseThrow()
                .source()
                .contains("owner-edited")
        );

        Flow deleted = service.delete(owner, draft.key(), true);
        assertEquals(3, deleted.version());
        assertTrue(service.draft(owner, draft.key()).isEmpty());
    }

    /**
     * Attempts to deploy another tenant's draft and checks that no runnable
     * version is created and the owner's draft version remains unchanged.
     */
    @Test
    void s10AnotherTenantCannotDeployTheDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        String raw = validYaml("uc01-s10", "deployable");
        Flow draft = saveDraft(service, owner, raw);

        assertThrows(
            WorkflowException.class,
            () -> deploy(service, other, draft.key())
        );
        assertTrue(service.latestFlow(owner, draft.key()).isEmpty());
        assertTrue(service.latestFlow(other, draft.key()).isEmpty());
        assertEquals(
            raw,
            service.draft(owner, draft.key()).orElseThrow().source()
        );
        assertEquals(
            draft.version(),
            service.draft(owner, draft.key()).orElseThrow().version()
        );

        Flow deleted = service.delete(owner, draft.key(), true);
        assertEquals(2, deleted.version());
        assertTrue(service.draft(owner, draft.key()).isEmpty());
    }

    /**
     * Deletes a draft, confirms it cannot be queried or deployed, then saves
     * the same key again without reusing the deleted version.
     */
    @Test
    void s11UserReusesTheFlowKeyAfterDeletingTheCurrentDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        Flow draft = saveDraft(service,
            user,
            validYaml("uc01-s11", "deleted")
        );
        assertEquals(1, draft.version());
        assertSameVisibleDraft(
            draft,
            service.draft(user, draft.key()).orElseThrow()
        );
        Flow deleted = service.delete(user, draft.key(), true);
        assertNotEquals(draft.id(), deleted.id());
        assertEquals(2, deleted.version());
        assertTrue(service.draft(user, draft.key()).isEmpty());

        assertThrows(
            WorkflowException.class,
            () -> deploy(service, user, draft.key())
        );
        assertTrue(service.latestFlow(user, draft.key()).isEmpty());

        Flow replacement = saveDraft(service,
            user,
            draft.key(),
            validYaml("uc01-s11", "replacement")
        );
        assertNotEquals(draft.id(), replacement.id());
        assertNotEquals(deleted.id(), replacement.id());
        assertEquals(3, replacement.version());
        assertTrue(replacement.version() > deleted.version());
        assertSameVisibleDraft(
            replacement,
            service.draft(user, draft.key()).orElseThrow()
        );

        Flow replacementDeleted = service.delete(
            user,
            replacement.key(),
            true
        );
        assertEquals(4, replacementDeleted.version());
        assertTrue(service.draft(user, replacement.key()).isEmpty());
    }

    /**
     * Uses the returned draft version in the runnable-Flow query and checks
     * that the draft remains visible only through the draft query.
     */
    @Test
    void s12DraftAndDeployedFlowQueriesStaySeparate() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String raw = validYaml("uc01-s12", "draft-only");
        Flow draft = saveDraft(service, user, raw);

        assertTrue(service.latestFlow(user, draft.key()).isEmpty());
        assertEquals(1, draft.version());
        assertTrue(service.flow(
            user,
            draft.key(),
            draft.version()
        ).isEmpty());
        assertEquals(
            raw,
            service.draft(user, draft.key()).orElseThrow().source()
        );
        assertEquals(
            draft.version(),
            service.draft(user, draft.key()).orElseThrow().version()
        );

        Flow deleted = service.delete(user, draft.key(), true);
        assertEquals(2, deleted.version());
        assertTrue(service.draft(user, draft.key()).isEmpty());
    }

    /**
     * Saves source containing system-managed fields and checks that only the
     * raw source preserves them while persisted Flow facts remain assigned by
     * the system.
     */
    @Test
    void s13SystemFieldsRemainRawDraftSourceOnly() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String raw = """
            id: user-controlled-id
            companyId: injected-company
            key: uc01-s13
            version: 99
            draft: false
            status: Delete
            creator:
              id: injected-user
            updater:
              id: injected-updater
            deleter:
              id: injected-deleter
            createdAt: 1
            updatedAt: 2
            deletedAt: 3
            tasks:
              - key: start
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """;
        Flow draft = saveDraft(service, user, raw);
        assertNotEquals("user-controlled-id", draft.id());
        assertEquals(user.getCompanyId(), draft.companyId());
        assertEquals(1, draft.version());
        assertTrue(draft.draft());
        assertFalse(draft.deleted());
        assertEquals(user.getUser().getId(), draft.creator().id());
        assertEquals(user.getUser().getId(), draft.updater().id());
        assertTrue(draft.deleter().isEmpty());
        assertTrue(draft.deletedAt().isEmpty());
        assertNotEquals(1, draft.createdAt());
        assertNotEquals(2, draft.updatedAt());
        assertEquals(raw, draft.source());

        Flow queried = service.draft(user, draft.key()).orElseThrow();
        assertSameVisibleDraft(draft, queried);
        assertThrows(
            RuntimeException.class,
            () -> deploy(service, user, draft.key())
        );
        assertSameVisibleDraft(
            queried,
            service.draft(user, draft.key()).orElseThrow()
        );
        assertTrue(service.flow(
            user,
            draft.key(),
            draft.version()
        ).isEmpty());
        assertTrue(service.latestFlow(user, draft.key()).isEmpty());

        Flow deleted = service.delete(user, draft.key(), true);
        assertEquals(2, deleted.version());
        assertTrue(service.draft(user, draft.key()).isEmpty());
        assertTrue(service.latestFlow(user, draft.key()).isEmpty());
    }

    /**
     * Compares the draft facts exposed by save and query operations without
     * depending on their internal Task materialization strategy.
     *
     * @param expected non-null draft facts returned by an earlier public
     *        operation; not modified
     * @param actual non-null draft facts returned by a later public query; not
     *        modified
     */
    private static void assertSameVisibleDraft(
        Flow expected,
        Flow actual
    ) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.companyId(), actual.companyId());
        assertEquals(expected.key(), actual.key());
        assertEquals(expected.version(), actual.version());
        assertEquals(expected.source(), actual.source());
        assertEquals(expected.creator(), actual.creator());
        assertEquals(expected.createdAt(), actual.createdAt());
        assertEquals(expected.updater(), actual.updater());
        assertEquals(expected.updatedAt(), actual.updatedAt());
        assertEquals(expected.deleted(), actual.deleted());
        assertEquals(expected.draft(), actual.draft());
    }

    private static Flow saveDraft(
        FlowService service,
        Session<User> session,
        String raw
    ) {
        return service.save(session, PublishFlowCommand.from(raw));
    }

    private static Flow saveDraft(
        FlowService service,
        Session<User> session,
        String flowKey,
        String raw
    ) {
        return service.save(
            session,
            PublishFlowCommand.from(flowKey, raw)
        );
    }

    private static Flow deploy(
        FlowService service,
        Session<User> session,
        String flowKey
    ) {
        return service.save(
            session,
            PublishFlowCommand.from(flowKey, false)
        );
    }

    private WorkflowUcFixture fixture() {
        WorkflowUcFixture fixture = WorkflowUcFixture.open();
        fixtures.add(fixture);
        return fixture;
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

    private static Session<User> session(
        String companyId,
        String userId,
        String userName
    ) {
        User user = new User();
        user.setId(userId);
        user.setName(userName);
        user.setCompanyId(companyId);
        Session<User> session = new Session<>();
        session.setUser(user);
        return session;
    }
}
