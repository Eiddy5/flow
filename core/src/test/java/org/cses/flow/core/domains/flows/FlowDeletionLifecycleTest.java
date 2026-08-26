package org.cses.flow.core.domains.flows;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.utils.SessionUtil;
import org.junit.jupiter.api.Test;
import org.paas.session.RecordState;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDeletionLifecycleTest {

    private static final Session<User> SESSION = session();
    private static final ActorRef ACTOR = SessionUtil.user(SESSION);
    private static final long CREATED_AT = 1_785_312_000_000L;
    private static final Context PLUGINS = builtInContext();

    @Test
    void draftDeletesOnlyOnce() {
        Flow draft = Flow.create(
            SESSION,
            "lifecycle-flow",
            "",
            Map.of(),
            List.of(),
            List.of(),
            "key: lifecycle-flow"
        );
        long createdAt = draft.createdAt();

        assertFalse(draft.deleted());
        assertEquals(0L, draft.lockVersion());

        draft.delete(SESSION, createdAt + 1_000L);

        assertTrue(draft.deleted());
        assertEquals(1L, draft.lockVersion());
        assertEquals(ACTOR, draft.deleter().orElseThrow());
        assertEquals(
            createdAt + 1_000L,
            draft.deletedAt().orElseThrow()
        );
        assertThrows(
            WorkflowException.class,
            () -> draft.delete(SESSION, createdAt + 2_000L)
        );
        assertThrows(
            WorkflowException.class,
            () -> draft.revise(
                "",
                Map.of(),
                List.of(),
                List.of(),
                "key: restored-flow",
                SESSION,
                createdAt + 2_000L
            )
        );
    }

    @Test
    void deployedFlowDeletesWithoutNewReversion() {
        Flow flow = deploy(null);

        assertFalse(flow.deleted());
        assertEquals(1L, flow.reversion());

        flow.delete(SESSION, flow.createdAt() + 1_000L);

        assertTrue(flow.deleted());
        assertEquals(1L, flow.reversion());
        assertEquals(ACTOR, flow.deleter().orElseThrow());
        assertThrows(
            WorkflowException.class,
            () -> flow.delete(SESSION, flow.createdAt() + 2_000L)
        );
        assertThrows(WorkflowException.class, () -> deploy(flow));
    }

    @Test
    void eachPublishedVersionGetsADistinctBusinessIdentity() {
        Flow first = deploy(null);
        Flow second = deploy(first);

        assertNotEquals(first.id(), second.id());
        assertEquals(first.key(), second.key());
        assertEquals(1L, first.version());
        assertEquals(2L, second.version());
    }

    @Test
    void rehydrateRejectsInvalidDeletionAuditCombinations() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Flow.rehydrate(
                "draft-1",
                "company-1",
                "lifecycle-flow",
                true,
                null,
                "",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                RecordState.Open,
                ACTOR,
                ACTOR,
                ACTOR,
                CREATED_AT,
                CREATED_AT,
                CREATED_AT,
                "key: lifecycle-flow",
                0
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Flow.rehydrate(
                "draft-1",
                "company-1",
                "lifecycle-flow",
                true,
                null,
                "",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                RecordState.Delete,
                ACTOR,
                ACTOR,
                null,
                CREATED_AT,
                CREATED_AT,
                null,
                "key: lifecycle-flow",
                0
            )
        );

        Flow deployed = deploy(null);
        assertThrows(
            IllegalArgumentException.class,
            () -> rehydrate(
                deployed,
                RecordState.Open,
                ACTOR,
                CREATED_AT
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> rehydrate(deployed, RecordState.Delete, null, null)
        );
    }

    private static Flow deploy(Flow latest) {
        return PLUGINS.deploy(
            "company-1",
            "flow-1",
            Map.of(
                "key", "lifecycle-flow",
                "tasks", List.of(Map.of(
                    "key", "start",
                    "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                ))
            ),
            latest,
            ACTOR,
            CREATED_AT
        );
    }

    private static Flow rehydrate(
        Flow flow,
        RecordState status,
        ActorRef deleter,
        Long deletedAt
    ) {
        return Flow.rehydrate(
            flow.id(),
            flow.companyId(),
            flow.key(),
            false,
            flow.reversion(),
            flow.description(),
            flow.variables(),
            flow.inputs(),
            flow.outputs(),
            flow.tasks(),
            status,
            flow.creator(),
            flow.updater(),
            deleter,
            flow.createdAt(),
            flow.updatedAt(),
            deletedAt,
            flow.source(),
            flow.lockVersion()
        );
    }

    private static Session<User> session() {
        User user = new User();
        user.setId("lifecycle-user");
        user.setName("Lifecycle User");
        user.setCompanyId("company-1");

        Session<User> session = new Session<>();
        session.setCompanyId("company-1");
        session.setUser(user);
        return session;
    }
}
