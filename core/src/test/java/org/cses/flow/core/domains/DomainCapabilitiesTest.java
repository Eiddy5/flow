package org.cses.flow.core.domains;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.expressions.Express;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.TaskRoute;
import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DomainCapabilitiesTest {

    private static final long CREATED_AT = 1_786_003_200_000L;

    @Test
    void domainObjectsDeclareOnlyCapabilitiesTheyActuallyOwn() {
        assertTrue(Identified.class.isAssignableFrom(Flow.class));
        assertTrue(Identified.class.isAssignableFrom(FlowDraft.class));
        assertTrue(Identified.class.isAssignableFrom(Execution.class));
        assertTrue(Identified.class.isAssignableFrom(ExternalTask.class));
        assertTrue(Identified.class.isAssignableFrom(Task.class));
        assertTrue(Identified.class.isAssignableFrom(TaskRun.class));
        assertTrue(Auditable.class.isAssignableFrom(Flow.class));
        assertTrue(Auditable.class.isAssignableFrom(FlowDraft.class));
        assertTrue(Deletable.class.isAssignableFrom(Flow.class));
        assertTrue(Deletable.class.isAssignableFrom(FlowDraft.class));
        assertTrue(Lockable.class.isAssignableFrom(FlowDraft.class));
        assertTrue(Lockable.class.isAssignableFrom(Execution.class));
        assertTrue(Lockable.class.isAssignableFrom(ExternalTask.class));

        assertFalse(Auditable.class.isAssignableFrom(Execution.class));
        assertFalse(Deletable.class.isAssignableFrom(Execution.class));
        assertFalse(Lockable.class.isAssignableFrom(TaskRun.class));
        assertFalse(Identified.class.isAssignableFrom(ActorRef.class));
        assertFalse(Identified.class.isAssignableFrom(FlowId.class));
        assertFalse(Identified.class.isAssignableFrom(Input.class));
        assertFalse(Identified.class.isAssignableFrom(Output.class));
        assertFalse(Identified.class.isAssignableFrom(State.class));
        assertFalse(Identified.class.isAssignableFrom(TaskRoute.class));
        assertFalse(Identified.class.isAssignableFrom(Express.class));
        assertFalse(
            Identified.class.isAssignableFrom(TemplateExpression.class)
        );
        assertFalse(Identified.class.isAssignableFrom(RunResult.class));
    }

    @Test
    void flowDraftClosesIdentityAuditDeletionAndLockCapabilities() {
        Session<User> creatorSession = session(
            "company-1",
            "creator-1",
            "Creator"
        );
        Session<User> editorSession = session(
            "company-1",
            "editor-1",
            "Editor"
        );
        Session<User> deleterSession = session(
            "company-1",
            "deleter-1",
            "Deleter"
        );
        ActorRef creator = ActorRef.from(creatorSession);
        FlowDraft draft = FlowDraft.create(
            "company-1",
            "key: capability-flow",
            creator,
            CREATED_AT
        );

        Identified identified = draft;
        Auditable<FlowDraft> auditable = draft;
        Deletable<FlowDraft> deletable = draft;
        Lockable<FlowDraft> lockable = draft;

        assertEquals(draft.id(), identified.identifier());
        assertTrue(identified.identifiedBy("  " + draft.id() + "  "));
        assertFalse(identified.identifiedBy("another-draft"));
        identified.requireIdentifier(draft.id());
        assertThrows(
            WorkflowException.class,
            () -> identified.requireIdentifier("another-draft")
        );
        assertEquals(creator, auditable.creator());
        assertEquals(creator, auditable.updater());
        assertEquals(CREATED_AT, auditable.createdAt());
        assertEquals(CREATED_AT, auditable.updatedAt());
        assertTrue(deletable.deleter().isEmpty());
        assertTrue(deletable.deletedAt().isEmpty());
        assertTrue(lockable.hasLockVersion(0));
        lockable.requireLockVersion(0);
        assertThrows(
            WorkflowException.class,
            () -> lockable.requireLockVersion(1)
        );

        auditable.updateAudit(editorSession, CREATED_AT + 1_000L);

        assertEquals(ActorRef.from(editorSession), auditable.updater());
        assertEquals(CREATED_AT + 1_000L, auditable.updatedAt());
        assertTrue(lockable.hasLockVersion(1));
        lockable.requireLockVersion(1);

        deletable.delete(deleterSession, CREATED_AT + 2_000L);

        assertTrue(deletable.isDeleted());
        assertEquals(
            ActorRef.from(deleterSession),
            deletable.deleter().orElseThrow()
        );
        assertEquals(
            CREATED_AT + 2_000L,
            deletable.deletedAt().orElseThrow()
        );
        assertEquals(ActorRef.from(deleterSession), auditable.updater());
        assertEquals(CREATED_AT + 2_000L, auditable.updatedAt());
        assertTrue(lockable.hasLockVersion(2));
        assertThrows(
            WorkflowException.class,
            () -> deletable.delete(
                deleterSession,
                CREATED_AT + 3_000L
            )
        );
        assertThrows(
            WorkflowException.class,
            () -> auditable.updateAudit(
                editorSession,
                CREATED_AT + 3_000L
            )
        );
        assertThrows(WorkflowException.class, lockable::lock);
        assertEquals(2, lockable.lockVersion());
    }

    @Test
    void tenantContextIsPartOfAuditAndDeletionAuthorization() {
        Session<User> ownerSession = session(
            "company-1",
            "owner-1",
            "Owner"
        );
        FlowDraft draft = FlowDraft.create(
            "company-1",
            "key: tenant-capability-flow",
            ActorRef.from(ownerSession),
            CREATED_AT
        );
        Session<User> anotherCompany = session(
            "company-2",
            "user-2",
            "Another company user"
        );

        assertThrows(
            WorkflowException.class,
            () -> draft.delete(anotherCompany, CREATED_AT + 1_000L)
        );

        assertFalse(draft.isDeleted());
        assertTrue(draft.deleter().isEmpty());
        assertTrue(draft.deletedAt().isEmpty());
        assertEquals(0, draft.lockVersion());
    }

    private static Session<User> session(
        String companyId,
        String userId,
        String userName
    ) {
        User user = new User();
        user.setId(userId);
        user.setName(userName);
        user.setUserName(userName);
        user.setCompanyId(companyId);

        Session<User> session = new Session<>();
        session.setId("session-" + userId);
        session.setCompanyId(companyId);
        session.setUser(user);
        return session;
    }
}
