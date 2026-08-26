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

    @Test
    void s1UserCompletesTheDraftLifecycle() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String initial = validYaml("uc01-s1", "initial");
        String revised = validYaml("uc01-s1", "revised");

        Flow created = saveDraft(service, user, initial);
        assertFalse(created.id().isBlank());
        assertFalse(created.deleted());
        assertEquals(
            initial,
            service.draft(user, created.key()).orElseThrow().source()
        );

        Flow edited = saveDraft(service,
            user,
            created.key(),
            revised
        );
        assertEquals(created.id(), edited.id());
        assertEquals(
            revised,
            service.draft(user, created.key()).orElseThrow().source()
        );
        assertTrue(service.latestFlow(user, created.key()).isEmpty());

        Flow deleted = service.delete(user, created.key(), true);
        assertTrue(deleted.deleted());
        assertTrue(service.draft(user, created.key()).isEmpty());
    }

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
        assertTrue(service.latestFlow(user, draft.key()).isEmpty());

        saveDraft(service, user, draft.key(), """
            key: uc01-invalid
            tasks:
              - key: still-unresolved
                type: missing.AnotherPlugin
            """);
        service.delete(user, draft.key(), true);
        assertTrue(service.draft(user, draft.key()).isEmpty());
        assertTrue(service.latestFlow(user, draft.key()).isEmpty());
    }

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
        assertEquals(
            draftA.id(),
            service.draft(tenantA, draftB.key()).orElseThrow().id()
        );
        assertEquals(
            draftB.id(),
            service.draft(tenantB, draftA.key()).orElseThrow().id()
        );

        saveDraft(service,
            tenantA,
            draftA.key(),
            validYaml("uc01-s3", "tenant-a")
        );
        assertEquals(
            sameRaw,
            service.draft(tenantB, draftB.key()).orElseThrow().source()
        );
        saveDraft(service,
            tenantB,
            draftB.key(),
            validYaml("uc01-s3", "tenant-b")
        );

        service.delete(tenantA, draftA.key(), true);
        assertTrue(service.draft(tenantA, draftA.key()).isEmpty());
        assertTrue(service.draft(tenantB, draftB.key()).isPresent());
        service.delete(tenantB, draftB.key(), true);
        assertTrue(service.draft(tenantB, draftB.key()).isEmpty());
    }

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
        saveDraft(service,
            editor,
            created.key(),
            validYaml("uc01-s4", "edited")
        );
        Flow edited = service.draft(
            creator,
            created.key()
        ).orElseThrow();

        assertEquals("creator-user", edited.creator().id());
        assertEquals(created.createdAt(), edited.createdAt());
        assertEquals("editor-user", edited.updater().id());
        assertTrue(edited.updatedAt() >= seenByEditor.updatedAt());
        assertTrue(edited.source().contains("edited"));

        service.delete(creator, created.key(), true);
        assertTrue(service.draft(creator, created.key()).isEmpty());
    }

    @Test
    void s5WrongDraftKeysDoNotAffectTheControlDraft() {
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

        service.delete(user, control.key(), true);
        assertTrue(service.draft(user, control.key()).isEmpty());
    }

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

        assertThrows(
            IllegalArgumentException.class,
            () -> saveDraft(service, noTenant, "raw")
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

        service.delete(owner, control.key(), true);
        assertTrue(service.draft(owner, control.key()).isEmpty());
    }

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
        service.delete(owner, draft.key(), true);
        assertTrue(service.draft(owner, draft.key()).isEmpty());
    }

    @Test
    void s8AnotherTenantGetsAnIndependentDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        Flow draft = saveDraft(service,
            owner,
            validYaml("uc01-s8", "owner")
        );
        Flow before = service.draft(
            owner,
            draft.key()
        ).orElseThrow();

        Flow otherDraft = saveDraft(service,
            other,
            draft.key(),
            validYaml("uc01-s8", "intruder")
        );
        assertNotEquals(draft.id(), otherDraft.id());
        assertEquals(
            before,
            service.draft(owner, draft.key()).orElseThrow()
        );
        assertTrue(
            service.draft(other, draft.key())
                .orElseThrow()
                .source()
                .contains("intruder")
        );

        service.delete(owner, draft.key(), true);
        assertTrue(service.draft(owner, draft.key()).isEmpty());
        service.delete(other, otherDraft.key(), true);
        assertTrue(service.draft(other, otherDraft.key()).isEmpty());
    }

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
        saveDraft(service,
            owner,
            draft.key(),
            validYaml("uc01-s9", "owner-edited")
        );
        assertTrue(
            service.draft(owner, draft.key())
                .orElseThrow()
                .source()
                .contains("owner-edited")
        );

        service.delete(owner, draft.key(), true);
        assertTrue(service.draft(owner, draft.key()).isEmpty());
    }

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

        service.delete(owner, draft.key(), true);
        assertTrue(service.draft(owner, draft.key()).isEmpty());
    }

    @Test
    void s11DeletedDraftCannotBeModifiedOrDeployed() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        Flow draft = saveDraft(service,
            user,
            validYaml("uc01-s11", "deleted")
        );
        service.delete(user, draft.key(), true);

        assertThrows(
            WorkflowException.class,
            () -> saveDraft(service,
                user,
                draft.key(),
                validYaml("uc01-s11", "revived")
            )
        );
        assertThrows(
            WorkflowException.class,
            () -> deploy(service, user, draft.key())
        );
        assertTrue(service.draft(user, draft.key()).isEmpty());
        assertTrue(service.latestFlow(user, draft.key()).isEmpty());
    }

    @Test
    void s12DraftAndDeployedFlowQueriesStaySeparate() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String raw = validYaml("uc01-s12", "draft-only");
        Flow draft = saveDraft(service, user, raw);

        assertTrue(service.latestFlow(user, draft.key()).isEmpty());
        assertTrue(service.flow(user, draft.key(), 1).isEmpty());
        assertEquals(
            raw,
            service.draft(user, draft.key()).orElseThrow().source()
        );

        service.delete(user, draft.key(), true);
        assertTrue(service.draft(user, draft.key()).isEmpty());
    }

    @Test
    void s13SystemFieldsCannotBeInjectedIntoDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String raw = """
            id: user-controlled-id
            key: uc01-s13
            reversion: 99
            draft: false
            deleted: true
            creator:
              id: injected-user
            tasks:
              - key: start
                type: org.cses.flow.extensions.tasks.AutomaticTask
            """;
        assertThrows(
            IllegalArgumentException.class,
            () -> saveDraft(service, user, raw)
        );
        assertTrue(service.draft(user, "uc01-s13").isEmpty());
        assertTrue(service.latestFlow(user, "uc01-s13").isEmpty());
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
                type: org.cses.flow.extensions.tasks.AutomaticTask
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
