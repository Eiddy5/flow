package org.cses.flow.core.services.flows;

import org.cses.flow.core.domains.flows.FlowWithSource;
import org.cses.flow.core.exceptions.shared.WorkflowException;
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
    void s1UserCompletesTheSourceLifecycle() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String initial = validYaml("uc01-s1", "initial");
        String revised = validYaml("uc01-s1", "revised");

        FlowWithSource created = service.saveDraft(user, initial);
        assertFalse(created.id().isBlank());
        assertEquals(
            initial,
            service.source(user, created.id()).orElseThrow().raw()
        );

        FlowWithSource edited = service.saveDraft(
            user,
            created.id(),
            revised
        );
        assertEquals(created.id(), edited.id());
        assertEquals(
            revised,
            service.source(user, created.id()).orElseThrow().raw()
        );
        assertTrue(service.latestFlow(user, created.id()).isEmpty());

        service.discardDraft(user, created.id());
        assertTrue(service.source(user, created.id()).isEmpty());
    }

    @Test
    void s2UserRecoversFromAnInvalidDeployment() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String invalid = "key: [invalid";

        FlowWithSource source = service.saveDraft(user, invalid);
        assertEquals(
            invalid,
            service.source(user, source.id()).orElseThrow().raw()
        );
        assertThrows(
            RuntimeException.class,
            () -> service.deploy(user, source.id())
        );
        assertEquals(
            invalid,
            service.source(user, source.id()).orElseThrow().raw()
        );
        assertTrue(service.latestFlow(user, source.id()).isEmpty());

        service.saveDraft(user, source.id(), "still invalid: [");
        service.discardDraft(user, source.id());
        assertTrue(service.source(user, source.id()).isEmpty());
        assertTrue(service.latestFlow(user, source.id()).isEmpty());
    }

    @Test
    void s3TenantsManageIdenticalSourcesIndependently() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> tenantA = fixture.session();
        Session<User> tenantB = fixture.sessionFor("tenant-b");
        String sameRaw = validYaml("uc01-s3", "same");

        FlowWithSource sourceA = service.saveDraft(tenantA, sameRaw);
        FlowWithSource sourceB = service.saveDraft(tenantB, sameRaw);
        assertNotEquals(sourceA.id(), sourceB.id());
        assertTrue(service.source(tenantA, sourceB.id()).isEmpty());
        assertTrue(service.source(tenantB, sourceA.id()).isEmpty());

        service.saveDraft(
            tenantA,
            sourceA.id(),
            validYaml("uc01-s3", "tenant-a")
        );
        assertEquals(
            sameRaw,
            service.source(tenantB, sourceB.id()).orElseThrow().raw()
        );
        service.saveDraft(
            tenantB,
            sourceB.id(),
            validYaml("uc01-s3", "tenant-b")
        );

        service.discardDraft(tenantA, sourceA.id());
        assertTrue(service.source(tenantA, sourceA.id()).isEmpty());
        assertTrue(service.source(tenantB, sourceB.id()).isPresent());
        service.discardDraft(tenantB, sourceB.id());
        assertTrue(service.source(tenantB, sourceB.id()).isEmpty());
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

        FlowWithSource created = service.saveDraft(
            creator,
            validYaml("uc01-s4", "created")
        );
        FlowWithSource seenByEditor = service.source(
            editor,
            created.id()
        ).orElseThrow();
        service.saveDraft(
            editor,
            created.id(),
            validYaml("uc01-s4", "edited")
        );
        FlowWithSource edited = service.source(
            creator,
            created.id()
        ).orElseThrow();

        assertEquals("creator-user", edited.creator().id());
        assertEquals(created.createdAt(), edited.createdAt());
        assertEquals("editor-user", edited.updater().id());
        assertTrue(edited.updatedAt() >= seenByEditor.updatedAt());
        assertTrue(edited.raw().contains("edited"));

        service.discardDraft(creator, created.id());
        assertTrue(service.source(creator, created.id()).isEmpty());
    }

    @Test
    void s5WrongSourceIdsDoNotAffectTheControlSource() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        FlowWithSource control = service.saveDraft(
            user,
            validYaml("uc01-s5", "control")
        );
        FlowWithSource before = service.source(
            user,
            control.id()
        ).orElseThrow();

        assertTrue(service.source(user, "missing-source-id").isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () -> service.source(user, " ")
        );
        assertEquals(
            before,
            service.source(user, control.id()).orElseThrow()
        );

        service.discardDraft(user, control.id());
        assertTrue(service.source(user, control.id()).isEmpty());
    }

    @Test
    void s6MissingTenantIdentityCannotManageSources() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> noTenant = session(null, "no-tenant", "No tenant");
        FlowWithSource control = service.saveDraft(
            owner,
            validYaml("uc01-s6", "control")
        );
        FlowWithSource before = service.source(
            owner,
            control.id()
        ).orElseThrow();

        assertThrows(
            IllegalArgumentException.class,
            () -> service.saveDraft(noTenant, "raw")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.source(noTenant, control.id())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.saveDraft(noTenant, control.id(), "changed")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.discardDraft(noTenant, control.id())
        );
        assertEquals(
            before,
            service.source(owner, control.id()).orElseThrow()
        );
        assertTrue(service.latestFlow(owner, control.id()).isEmpty());

        service.discardDraft(owner, control.id());
        assertTrue(service.source(owner, control.id()).isEmpty());
    }

    @Test
    void s7AnotherTenantCannotQueryTheSource() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        FlowWithSource source = service.saveDraft(
            owner,
            validYaml("uc01-s7", "owner")
        );
        FlowWithSource before = service.source(
            owner,
            source.id()
        ).orElseThrow();

        assertTrue(service.source(other, source.id()).isEmpty());
        assertEquals(
            before,
            service.source(owner, source.id()).orElseThrow()
        );
        service.discardDraft(owner, source.id());
        assertTrue(service.source(owner, source.id()).isEmpty());
    }

    @Test
    void s8AnotherTenantCannotModifyTheSource() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        FlowWithSource source = service.saveDraft(
            owner,
            validYaml("uc01-s8", "owner")
        );
        FlowWithSource before = service.source(
            owner,
            source.id()
        ).orElseThrow();

        assertThrows(
            WorkflowException.class,
            () -> service.saveDraft(
                other,
                source.id(),
                validYaml("uc01-s8", "intruder")
            )
        );
        assertEquals(
            before,
            service.source(owner, source.id()).orElseThrow()
        );

        service.discardDraft(owner, source.id());
        assertTrue(service.source(owner, source.id()).isEmpty());
    }

    @Test
    void s9AnotherTenantCannotDeleteTheSource() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        FlowWithSource source = service.saveDraft(
            owner,
            validYaml("uc01-s9", "owner")
        );

        assertThrows(
            WorkflowException.class,
            () -> service.discardDraft(other, source.id())
        );
        service.saveDraft(
            owner,
            source.id(),
            validYaml("uc01-s9", "owner-edited")
        );
        assertTrue(
            service.source(owner, source.id())
                .orElseThrow()
                .raw()
                .contains("owner-edited")
        );

        service.discardDraft(owner, source.id());
        assertTrue(service.source(owner, source.id()).isEmpty());
    }

    @Test
    void s10AnotherTenantCannotDeployTheSource() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> owner = fixture.session();
        Session<User> other = fixture.sessionFor("tenant-b");
        String raw = validYaml("uc01-s10", "deployable");
        FlowWithSource source = service.saveDraft(owner, raw);

        assertThrows(
            WorkflowException.class,
            () -> service.deploy(other, source.id())
        );
        assertTrue(service.latestFlow(owner, source.id()).isEmpty());
        assertTrue(service.latestFlow(other, source.id()).isEmpty());
        assertEquals(
            raw,
            service.source(owner, source.id()).orElseThrow().raw()
        );

        service.discardDraft(owner, source.id());
        assertTrue(service.source(owner, source.id()).isEmpty());
    }

    @Test
    void s11DeletedSourceCannotBeModifiedOrDeployed() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        FlowWithSource source = service.saveDraft(
            user,
            validYaml("uc01-s11", "deleted")
        );
        service.discardDraft(user, source.id());

        assertThrows(
            WorkflowException.class,
            () -> service.saveDraft(
                user,
                source.id(),
                validYaml("uc01-s11", "revived")
            )
        );
        assertThrows(
            WorkflowException.class,
            () -> service.deploy(user, source.id())
        );
        assertTrue(service.source(user, source.id()).isEmpty());
        assertTrue(service.latestFlow(user, source.id()).isEmpty());
    }

    @Test
    void s12SourceAndDeployedFlowQueriesStaySeparate() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String raw = validYaml("uc01-s12", "source-only");
        FlowWithSource source = service.saveDraft(user, raw);

        assertTrue(service.latestFlow(user, source.id()).isEmpty());
        assertTrue(service.flow(user, source.id(), 1).isEmpty());
        assertEquals(
            raw,
            service.source(user, source.id()).orElseThrow().raw()
        );

        service.discardDraft(user, source.id());
        assertTrue(service.source(user, source.id()).isEmpty());
    }

    @Test
    void s13SystemFieldsRemainOpaqueSourceAndCannotBeInjected() {
        WorkflowUcFixture fixture = fixture();
        FlowService service = fixture.flowService();
        Session<User> user = fixture.session();
        String raw = """
            id: user-controlled-id
            key: uc01-s13
            reversion: 99
            status: CLOSED
            creator:
              id: injected-user
            tasks:
              - key: start
                type: AUTO
            """;
        FlowWithSource source = service.saveDraft(user, raw);
        FlowWithSource before = service.source(
            user,
            source.id()
        ).orElseThrow();

        assertNotEquals("user-controlled-id", source.id());
        assertEquals(raw, before.raw());
        assertEquals(user.getUserId(), before.creator().id());
        assertThrows(
            IllegalArgumentException.class,
            () -> service.deploy(user, source.id())
        );
        assertEquals(
            before,
            service.source(user, source.id()).orElseThrow()
        );
        assertTrue(service.latestFlow(user, source.id()).isEmpty());

        service.discardDraft(user, source.id());
        assertTrue(service.source(user, source.id()).isEmpty());
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
                type: AUTO
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
