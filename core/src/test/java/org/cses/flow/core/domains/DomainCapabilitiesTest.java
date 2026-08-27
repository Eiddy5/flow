package org.cses.flow.core.domains;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.expressions.Express;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.TaskRoute;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.utils.SessionUtil;
import org.junit.jupiter.api.Test;
import org.paas.session.RecordState;
import org.paas.session.Session;
import org.paas.session.User;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DomainCapabilitiesTest {

    @Test
    void tenantAggregatesKeepOnlyCreateAndFullRehydratePaths() {
        assertFalse(Modifier.isAbstract(Flow.class.getModifiers()));
        assertConstructionPaths(Flow.class);
        assertConstructionPaths(Execution.class);

        assertEquals(3, BaseDomain.class.getDeclaredConstructors().length);
        assertEquals(3, Audited.class.getDeclaredConstructors().length);
    }

    @Test
    void domainObjectsDeclareOnlyCapabilitiesTheyActuallyOwn() {
        assertTrue(Identified.class.isAssignableFrom(Flow.class));
        assertTrue(Identified.class.isAssignableFrom(Execution.class));
        assertTrue(Identified.class.isAssignableFrom(Task.class));
        assertTrue(Identified.class.isAssignableFrom(TaskRun.class));
        assertTrue(BaseDomain.class.isAssignableFrom(Flow.class));
        assertTrue(Audited.class.isAssignableFrom(Flow.class));

        assertTrue(BaseDomain.class.isAssignableFrom(Execution.class));
        assertFalse(Identified.class.isAssignableFrom(ActorRef.class));
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
    void flowDraftClosesIdentityAuditDeletionCapabilities() {
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
        Flow draft = Flow.create(
            creatorSession,
            "capability-flow",
            "",
            Map.of(),
            List.of(),
            List.of(),
            "key: capability-flow"
        );
        ActorRef creator = SessionUtil.user(creatorSession);
        long createdAt = draft.createdAt();

        Identified identified = draft;
        Audited audited = draft;

        assertEquals(draft.id(), identified.id());
        assertFalse(draft.id().isBlank());
        assertEquals("capability-flow", draft.key());
        assertTrue(identified.identifiedBy(draft.id()));
        assertFalse(identified.identifiedBy("another-draft"));
        identified.requireIdentifier(draft.id());
        assertThrows(
            WorkflowException.class,
            () -> identified.requireIdentifier("another-draft")
        );
        assertEquals(creator, audited.creator());
        assertEquals(creator, audited.updater());
        assertEquals(createdAt, audited.createdAt());
        assertEquals(createdAt, audited.updatedAt());
        assertEquals(RecordState.Open, audited.status());
        assertTrue(audited.deleter().isEmpty());
        assertTrue(audited.deletedAt().isEmpty());
        draft.revise(
            "revised",
            Map.of(),
            List.of(),
            List.of(),
            "key: capability-flow\ndescription: revised",
            editorSession,
            createdAt + 1_000L
        );

        assertEquals(SessionUtil.user(editorSession), audited.updater());
        assertEquals(createdAt + 1_000L, audited.updatedAt());
        draft.delete(deleterSession, createdAt + 2_000L);

        assertTrue(audited.deleted());
        assertEquals(
            SessionUtil.user(deleterSession),
            audited.deleter().orElseThrow()
        );
        assertEquals(
            createdAt + 2_000L,
            audited.deletedAt().orElseThrow()
        );
        assertEquals(SessionUtil.user(deleterSession), audited.updater());
        assertEquals(createdAt + 2_000L, audited.updatedAt());
        assertThrows(
            WorkflowException.class,
            () -> draft.delete(
                deleterSession,
                createdAt + 3_000L
            )
        );
        assertThrows(
            WorkflowException.class,
            () -> draft.revise(
                "invalid",
                Map.of(),
                List.of(),
                List.of(),
                "key: capability-flow\ndescription: invalid",
                editorSession,
                createdAt + 3_000L
            )
        );
    }

    @Test
    void withStateAcceptsEveryRecordStateAndDeleteRemainsTerminal() {
        Session<User> operator = session(
            "company-1",
            "operator-1",
            "Operator"
        );
        ActorRef actor = SessionUtil.user(operator);
        Flow draft = Flow.create(
            operator,
            "state-compatible-flow",
            "",
            Map.of(),
            List.of(),
            List.of(),
            "key: state-compatible-flow"
        );
        long changedAt = draft.createdAt();

        for (RecordState status : RecordState.values()) {
            if (RecordState.Delete.equals(status)) {
                continue;
            }
            changedAt += 1_000L;
            draft.withState(status, operator, changedAt);
            assertEquals(status, draft.status());
        }

        changedAt += 1_000L;
        draft.withState(RecordState.Delete, operator, changedAt);

        assertEquals(RecordState.Delete, draft.status());
        assertEquals(actor, draft.updater());
        assertEquals(actor, draft.deleter().orElseThrow());
        assertEquals(draft.updatedAt(), draft.deletedAt().orElseThrow());
        long terminalTime = changedAt;
        assertThrows(
            WorkflowException.class,
            () -> draft.withState(
                RecordState.Open,
                operator,
                terminalTime + 1_000L
            )
        );
    }

    @Test
    void tenantContextIsPartOfAuditAndDeletionAuthorization() {
        Session<User> ownerSession = session(
            "company-1",
            "owner-1",
            "Owner"
        );
        Flow draft = Flow.create(
            ownerSession,
            "tenant-capability-flow",
            "",
            Map.of(),
            List.of(),
            List.of(),
            "key: tenant-capability-flow"
        );
        Session<User> anotherCompany = session(
            "company-2",
            "user-2",
            "Another company user"
        );

        assertThrows(
            WorkflowException.class,
            () -> draft.delete(anotherCompany, draft.createdAt() + 1_000L)
        );

        assertFalse(draft.deleted());
        assertTrue(draft.deleter().isEmpty());
        assertTrue(draft.deletedAt().isEmpty());
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

    private static void assertConstructionPaths(Class<?> domainType) {
        assertTrue(Arrays.stream(domainType.getDeclaredConstructors())
            .noneMatch(constructor -> Modifier.isPublic(
                constructor.getModifiers()
            )));

        Set<String> factoryNames = Arrays.stream(domainType.getDeclaredMethods())
            .filter(method -> Modifier.isStatic(method.getModifiers()))
            .filter(method -> domainType.equals(method.getReturnType()))
            .map(Method::getName)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(factoryNames.contains("rehydrate"));
        assertTrue(factoryNames.contains("create"));

        Method create = Arrays.stream(domainType.getDeclaredMethods())
            .filter(method -> method.getName().equals("create"))
            .findFirst()
            .orElseThrow();
        assertTrue(Arrays.asList(create.getParameterTypes()).contains(
            Session.class
        ));

        Method rehydrate = Arrays.stream(domainType.getDeclaredMethods())
            .filter(method -> method.getName().equals("rehydrate"))
            .findFirst()
            .orElseThrow();
        assertFalse(Arrays.asList(rehydrate.getParameterTypes()).contains(
            Session.class
        ));
    }
}
