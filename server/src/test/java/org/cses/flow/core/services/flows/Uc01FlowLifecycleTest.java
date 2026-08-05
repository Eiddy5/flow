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
            service.draft(user, created.id()).orElseThrow().raw()
        );

        FlowDraft edited = service.saveDraft(
            user,
            created.id(),
            revised
        );
        assertEquals(created.id(), edited.id());
        assertEquals(
            revised,
            service.draft(user, created.id()).orElseThrow().raw()
        );
        assertTrue(service.latestFlow(user, created.id()).isEmpty());

        FlowDraft deleted = service.deleteDraft(user, created.id());
        assertTrue(deleted.isDeleted());
        assertTrue(service.draft(user, created.id()).isEmpty());
    }

    @Test
    void s2UserRecoversFromAnInvalidDeployment() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String invalid = "key: [invalid";

        FlowDraft draft = service.saveDraft(user, invalid);
        assertEquals(
            invalid,
            service.draft(user, draft.id()).orElseThrow().raw()
        );
        assertThrows(
            RuntimeException.class,
            () -> service.deploy(user, draft.id())
        );
        assertEquals(
            invalid,
            service.draft(user, draft.id()).orElseThrow().raw()
        );
        assertTrue(service.latestFlow(user, draft.id()).isEmpty());

        service.saveDraft(user, draft.id(), "still invalid: [");
        service.deleteDraft(user, draft.id());
        assertTrue(service.draft(user, draft.id()).isEmpty());
        assertTrue(service.latestFlow(user, draft.id()).isEmpty());
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
        assertTrue(service.draft(tenantA, draftB.id()).isEmpty());
        assertTrue(service.draft(tenantB, draftA.id()).isEmpty());

        service.saveDraft(
            tenantA,
            draftA.id(),
            validYaml("uc01-s3", "tenant-a")
        );
        assertEquals(
            sameRaw,
            service.draft(tenantB, draftB.id()).orElseThrow().raw()
        );
        service.saveDraft(
            tenantB,
            draftB.id(),
            validYaml("uc01-s3", "tenant-b")
        );

        service.deleteDraft(tenantA, draftA.id());
        assertTrue(service.draft(tenantA, draftA.id()).isEmpty());
        assertTrue(service.draft(tenantB, draftB.id()).isPresent());
        service.deleteDraft(tenantB, draftB.id());
        assertTrue(service.draft(tenantB, draftB.id()).isEmpty());
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
            created.id()
        ).orElseThrow();
        service.saveDraft(
            editor,
            created.id(),
            validYaml("uc01-s4", "edited")
        );
        FlowDraft edited = service.draft(
            creator,
            created.id()
        ).orElseThrow();

        assertEquals("creator-user", edited.creator().id());
        assertEquals(created.createdAt(), edited.createdAt());
        assertEquals("editor-user", edited.updater().id());
        assertTrue(edited.updatedAt() >= seenByEditor.updatedAt());
        assertTrue(edited.raw().contains("edited"));

        service.deleteDraft(creator, created.id());
        assertTrue(service.draft(creator, created.id()).isEmpty());
    }

    @Test
    void s5WrongDraftIdsDoNotAffectTheControlDraft() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        FlowDraft control = service.saveDraft(
            user,
            validYaml("uc01-s5", "control")
        );
        FlowDraft before = service.draft(
            user,
            control.id()
        ).orElseThrow();

        assertTrue(service.draft(user, "missing-draft-id").isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () -> service.draft(user, " ")
        );
        assertEquals(
            before,
            service.draft(user, control.id()).orElseThrow()
        );

        service.deleteDraft(user, control.id());
        assertTrue(service.draft(user, control.id()).isEmpty());
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
            control.id()
        ).orElseThrow();

        assertThrows(
            IllegalArgumentException.class,
            () -> service.saveDraft(noTenant, "raw")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.draft(noTenant, control.id())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.saveDraft(noTenant, control.id(), "changed")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.deleteDraft(noTenant, control.id())
        );
        assertEquals(
            before,
            service.draft(owner, control.id()).orElseThrow()
        );
        assertTrue(service.latestFlow(owner, control.id()).isEmpty());

        service.deleteDraft(owner, control.id());
        assertTrue(service.draft(owner, control.id()).isEmpty());
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
            draft.id()
        ).orElseThrow();

        assertTrue(service.draft(other, draft.id()).isEmpty());
        assertEquals(
            before,
            service.draft(owner, draft.id()).orElseThrow()
        );
        service.deleteDraft(owner, draft.id());
        assertTrue(service.draft(owner, draft.id()).isEmpty());
    }

    @Test
    void s8AnotherTenantCannotModifyTheDraft() {
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
            draft.id()
        ).orElseThrow();

        assertThrows(
            WorkflowException.class,
            () -> service.saveDraft(
                other,
                draft.id(),
                validYaml("uc01-s8", "intruder")
            )
        );
        assertEquals(
            before,
            service.draft(owner, draft.id()).orElseThrow()
        );

        service.deleteDraft(owner, draft.id());
        assertTrue(service.draft(owner, draft.id()).isEmpty());
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
            () -> service.deleteDraft(other, draft.id())
        );
        service.saveDraft(
            owner,
            draft.id(),
            validYaml("uc01-s9", "owner-edited")
        );
        assertTrue(
            service.draft(owner, draft.id())
                .orElseThrow()
                .raw()
                .contains("owner-edited")
        );

        service.deleteDraft(owner, draft.id());
        assertTrue(service.draft(owner, draft.id()).isEmpty());
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
            () -> service.deploy(other, draft.id())
        );
        assertTrue(service.latestFlow(owner, draft.id()).isEmpty());
        assertTrue(service.latestFlow(other, draft.id()).isEmpty());
        assertEquals(
            raw,
            service.draft(owner, draft.id()).orElseThrow().raw()
        );

        service.deleteDraft(owner, draft.id());
        assertTrue(service.draft(owner, draft.id()).isEmpty());
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
        service.deleteDraft(user, draft.id());

        assertThrows(
            WorkflowException.class,
            () -> service.saveDraft(
                user,
                draft.id(),
                validYaml("uc01-s11", "revived")
            )
        );
        assertThrows(
            WorkflowException.class,
            () -> service.deploy(user, draft.id())
        );
        assertTrue(service.draft(user, draft.id()).isEmpty());
        assertTrue(service.latestFlow(user, draft.id()).isEmpty());
    }

    @Test
    void s12DraftAndDeployedFlowQueriesStaySeparate() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String raw = validYaml("uc01-s12", "draft-only");
        FlowDraft draft = service.saveDraft(user, raw);

        assertTrue(service.latestFlow(user, draft.id()).isEmpty());
        assertTrue(service.flow(user, draft.id(), 1).isEmpty());
        assertEquals(
            raw,
            service.draft(user, draft.id()).orElseThrow().raw()
        );

        service.deleteDraft(user, draft.id());
        assertTrue(service.draft(user, draft.id()).isEmpty());
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
            draft.id()
        ).orElseThrow();

        assertNotEquals("user-controlled-id", draft.id());
        assertEquals(raw, before.raw());
        assertEquals(user.getUserId(), before.creator().id());
        assertThrows(
            IllegalArgumentException.class,
            () -> service.deploy(user, draft.id())
        );
        assertEquals(
            before,
            service.draft(user, draft.id()).orElseThrow()
        );
        assertTrue(service.latestFlow(user, draft.id()).isEmpty());

        service.deleteDraft(user, draft.id());
        assertTrue(service.draft(user, draft.id()).isEmpty());
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
