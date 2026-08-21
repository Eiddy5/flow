package org.cses.flow.core.services.flows;

import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
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

        FlowDraft created = service.saveDraft(user, initial);
        assertFalse(created.id().isBlank());
        assertFalse(created.isDeleted());
        assertEquals(
            initial,
            service.draft(user, created.flowKey()).orElseThrow().raw()
        );

        FlowDraft edited = service.saveDraft(
            user,
            created.flowKey(),
            revised
        );
        assertEquals(created.id(), edited.id());
        assertEquals(
            revised,
            service.draft(user, created.flowKey()).orElseThrow().raw()
        );
        assertTrue(service.latestFlow(user, created.flowKey()).isEmpty());

        FlowDraft deleted = service.deleteDraft(user, created.flowKey());
        assertTrue(deleted.isDeleted());
        assertTrue(service.draft(user, created.flowKey()).isEmpty());
    }

    @Test
    void s2UserRecoversFromAnInvalidDeployment() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String invalid = "key: [invalid";

        FlowDraft draft = service.saveDraft(user, "uc01-invalid", invalid);
        assertEquals(
            invalid,
            service.draft(user, draft.flowKey()).orElseThrow().raw()
        );
        assertThrows(
            RuntimeException.class,
            () -> service.deploy(user, draft.flowKey())
        );
        assertEquals(
            invalid,
            service.draft(user, draft.flowKey()).orElseThrow().raw()
        );
        assertTrue(service.latestFlow(user, draft.flowKey()).isEmpty());

        service.saveDraft(user, draft.flowKey(), "still invalid: [");
        service.deleteDraft(user, draft.flowKey());
        assertTrue(service.draft(user, draft.flowKey()).isEmpty());
        assertTrue(service.latestFlow(user, draft.flowKey()).isEmpty());
    }

    @Test
    void s3TenantsManageIdenticalDraftsIndependently() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> tenantA = fixture.session();
        Session<User> tenantB = fixture.sessionFor("tenant-b");
        String sameRaw = validYaml("uc01-s3", "same");

        FlowDraft draftA = service.saveDraft(tenantA, sameRaw);
        FlowDraft draftB = service.saveDraft(tenantB, sameRaw);
        assertNotEquals(draftA.id(), draftB.id());
        assertEquals(
            draftA.id(),
            service.draft(tenantA, draftB.flowKey()).orElseThrow().id()
        );
        assertEquals(
            draftB.id(),
            service.draft(tenantB, draftA.flowKey()).orElseThrow().id()
        );

        service.saveDraft(
            tenantA,
            draftA.flowKey(),
            validYaml("uc01-s3", "tenant-a")
        );
        assertEquals(
            sameRaw,
            service.draft(tenantB, draftB.flowKey()).orElseThrow().raw()
        );
        service.saveDraft(
            tenantB,
            draftB.flowKey(),
            validYaml("uc01-s3", "tenant-b")
        );

        service.deleteDraft(tenantA, draftA.flowKey());
        assertTrue(service.draft(tenantA, draftA.flowKey()).isEmpty());
        assertTrue(service.draft(tenantB, draftB.flowKey()).isPresent());
        service.deleteDraft(tenantB, draftB.flowKey());
        assertTrue(service.draft(tenantB, draftB.flowKey()).isEmpty());
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

        FlowDraft created = service.saveDraft(
            creator,
            validYaml("uc01-s4", "created")
        );
        FlowDraft seenByEditor = service.draft(
            editor,
            created.flowKey()
        ).orElseThrow();
        service.saveDraft(
            editor,
            created.flowKey(),
            validYaml("uc01-s4", "edited")
        );
        FlowDraft edited = service.draft(
            creator,
            created.flowKey()
        ).orElseThrow();

        assertEquals("creator-user", edited.creator().id());
        assertEquals(created.createdAt(), edited.createdAt());
        assertEquals("editor-user", edited.updater().id());
        assertTrue(edited.updatedAt() >= seenByEditor.updatedAt());
        assertTrue(edited.raw().contains("edited"));

        service.deleteDraft(creator, created.flowKey());
        assertTrue(service.draft(creator, created.flowKey()).isEmpty());
    }

    @Test
    void s5WrongDraftKeysDoNotAffectTheControlDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        FlowDraft control = service.saveDraft(
            user,
            validYaml("uc01-s5", "control")
        );
        FlowDraft before = service.draft(
            user,
            control.flowKey()
        ).orElseThrow();

        assertTrue(service.draft(user, "missing-draft-key").isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () -> service.draft(user, " ")
        );
        assertEquals(
            before,
            service.draft(user, control.flowKey()).orElseThrow()
        );

        service.deleteDraft(user, control.flowKey());
        assertTrue(service.draft(user, control.flowKey()).isEmpty());
    }

    @Test
    void s6MissingTenantIdentityCannotManageDrafts() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> noTenant = session(null, "no-tenant", "No tenant");
        FlowDraft control = service.saveDraft(
            owner,
            validYaml("uc01-s6", "control")
        );
        FlowDraft before = service.draft(
            owner,
            control.flowKey()
        ).orElseThrow();

        assertThrows(
            IllegalArgumentException.class,
            () -> service.saveDraft(noTenant, "raw")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.draft(noTenant, control.flowKey())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.saveDraft(noTenant, control.flowKey(), "changed")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.deleteDraft(noTenant, control.flowKey())
        );
        assertEquals(
            before,
            service.draft(owner, control.flowKey()).orElseThrow()
        );
        assertTrue(service.latestFlow(owner, control.flowKey()).isEmpty());

        service.deleteDraft(owner, control.flowKey());
        assertTrue(service.draft(owner, control.flowKey()).isEmpty());
    }

    @Test
    void s7AnotherTenantCannotQueryTheDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        FlowDraft draft = service.saveDraft(
            owner,
            validYaml("uc01-s7", "owner")
        );
        FlowDraft before = service.draft(
            owner,
            draft.flowKey()
        ).orElseThrow();

        assertTrue(service.draft(other, draft.flowKey()).isEmpty());
        assertEquals(
            before,
            service.draft(owner, draft.flowKey()).orElseThrow()
        );
        service.deleteDraft(owner, draft.flowKey());
        assertTrue(service.draft(owner, draft.flowKey()).isEmpty());
    }

    @Test
    void s8AnotherTenantGetsAnIndependentDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        FlowDraft draft = service.saveDraft(
            owner,
            validYaml("uc01-s8", "owner")
        );
        FlowDraft before = service.draft(
            owner,
            draft.flowKey()
        ).orElseThrow();

        FlowDraft otherDraft = service.saveDraft(
            other,
            draft.flowKey(),
            validYaml("uc01-s8", "intruder")
        );
        assertNotEquals(draft.id(), otherDraft.id());
        assertEquals(
            before,
            service.draft(owner, draft.flowKey()).orElseThrow()
        );
        assertTrue(
            service.draft(other, draft.flowKey())
                .orElseThrow()
                .raw()
                .contains("intruder")
        );

        service.deleteDraft(owner, draft.flowKey());
        assertTrue(service.draft(owner, draft.flowKey()).isEmpty());
        service.deleteDraft(other, otherDraft.flowKey());
        assertTrue(service.draft(other, otherDraft.flowKey()).isEmpty());
    }

    @Test
    void s9AnotherTenantCannotDeleteTheDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        FlowDraft draft = service.saveDraft(
            owner,
            validYaml("uc01-s9", "owner")
        );

        assertThrows(
            WorkflowException.class,
            () -> service.deleteDraft(other, draft.flowKey())
        );
        service.saveDraft(
            owner,
            draft.flowKey(),
            validYaml("uc01-s9", "owner-edited")
        );
        assertTrue(
            service.draft(owner, draft.flowKey())
                .orElseThrow()
                .raw()
                .contains("owner-edited")
        );

        service.deleteDraft(owner, draft.flowKey());
        assertTrue(service.draft(owner, draft.flowKey()).isEmpty());
    }

    @Test
    void s10AnotherTenantCannotDeployTheDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        String raw = validYaml("uc01-s10", "deployable");
        FlowDraft draft = service.saveDraft(owner, raw);

        assertThrows(
            WorkflowException.class,
            () -> service.deploy(other, draft.flowKey())
        );
        assertTrue(service.latestFlow(owner, draft.flowKey()).isEmpty());
        assertTrue(service.latestFlow(other, draft.flowKey()).isEmpty());
        assertEquals(
            raw,
            service.draft(owner, draft.flowKey()).orElseThrow().raw()
        );

        service.deleteDraft(owner, draft.flowKey());
        assertTrue(service.draft(owner, draft.flowKey()).isEmpty());
    }

    @Test
    void s11DeletedDraftCannotBeModifiedOrDeployed() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        FlowDraft draft = service.saveDraft(
            user,
            validYaml("uc01-s11", "deleted")
        );
        service.deleteDraft(user, draft.flowKey());

        assertThrows(
            WorkflowException.class,
            () -> service.saveDraft(
                user,
                draft.flowKey(),
                validYaml("uc01-s11", "revived")
            )
        );
        assertThrows(
            WorkflowException.class,
            () -> service.deploy(user, draft.flowKey())
        );
        assertTrue(service.draft(user, draft.flowKey()).isEmpty());
        assertTrue(service.latestFlow(user, draft.flowKey()).isEmpty());
    }

    @Test
    void s12DraftAndDeployedFlowQueriesStaySeparate() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String raw = validYaml("uc01-s12", "draft-only");
        FlowDraft draft = service.saveDraft(user, raw);

        assertTrue(service.latestFlow(user, draft.flowKey()).isEmpty());
        assertTrue(service.flow(user, draft.flowKey(), 1).isEmpty());
        assertEquals(
            raw,
            service.draft(user, draft.flowKey()).orElseThrow().raw()
        );

        service.deleteDraft(user, draft.flowKey());
        assertTrue(service.draft(user, draft.flowKey()).isEmpty());
    }

    @Test
    void s13SystemFieldsRemainOpaqueDraftAndCannotBeInjected() {
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
        FlowDraft draft = service.saveDraft(user, raw);
        FlowDraft before = service.draft(
            user,
            draft.flowKey()
        ).orElseThrow();

        assertNotEquals("user-controlled-id", draft.id());
        assertEquals(raw, before.raw());
        assertEquals(user.getUserId(), before.creator().id());
        assertThrows(
            IllegalArgumentException.class,
            () -> service.deploy(user, draft.flowKey())
        );
        assertEquals(
            before,
            service.draft(user, draft.flowKey()).orElseThrow()
        );
        assertTrue(service.latestFlow(user, draft.flowKey()).isEmpty());

        service.deleteDraft(user, draft.flowKey());
        assertTrue(service.draft(user, draft.flowKey()).isEmpty());
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
