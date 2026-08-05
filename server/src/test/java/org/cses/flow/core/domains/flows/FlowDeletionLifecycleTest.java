package org.cses.flow.core.domains.flows;

import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDeletionLifecycleTest {

    private static final ActorRef ACTOR =
        ActorRef.create("lifecycle-user", "Lifecycle User");
    private static final long CREATED_AT = 1_785_312_000_000L;
    private static final Context PLUGINS = builtInContext();

    @Test
    void draftDeletesOnlyOnce() {
        FlowDraft draft = FlowDraft.create(
            "company-1",
            "key: lifecycle-flow",
            ACTOR,
            CREATED_AT
        );

        assertFalse(draft.isDeleted());
        assertEquals(0L, draft.lockVersion());

        draft.delete(ACTOR, CREATED_AT + 1_000L);

        assertTrue(draft.isDeleted());
        assertEquals(1L, draft.lockVersion());
        assertEquals(ACTOR, draft.deleter().orElseThrow());
        assertEquals(
            CREATED_AT + 1_000L,
            draft.deletedAt().orElseThrow()
        );
        assertThrows(
            WorkflowException.class,
            () -> draft.delete(ACTOR, CREATED_AT + 2_000L)
        );
        assertThrows(
            WorkflowException.class,
            () -> draft.revise(
                "key: restored-flow",
                ACTOR,
                CREATED_AT + 2_000L
            )
        );
    }

    @Test
    void deployedFlowDeletesWithoutNewReversion() {
        Flow flow = deploy(null);

        assertFalse(flow.isDeleted());
        assertEquals(1L, flow.reversion());

        flow.delete(ACTOR, CREATED_AT + 1_000L);

        assertTrue(flow.isDeleted());
        assertEquals(1L, flow.reversion());
        assertEquals(ACTOR, flow.deleter().orElseThrow());
        assertThrows(
            WorkflowException.class,
            () -> flow.delete(ACTOR, CREATED_AT + 2_000L)
        );
        assertThrows(WorkflowException.class, () -> deploy(flow));
    }

    @Test
    void rehydrateRejectsInvalidDeletionAuditCombinations() {
        assertThrows(
            IllegalArgumentException.class,
            () -> FlowDraft.rehydrate(
                "draft-1",
                "company-1",
                "key: lifecycle-flow",
                false,
                ACTOR,
                ACTOR,
                ACTOR,
                CREATED_AT,
                CREATED_AT,
                CREATED_AT,
                0
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FlowDraft.rehydrate(
                "draft-1",
                "company-1",
                "key: lifecycle-flow",
                true,
                ACTOR,
                ACTOR,
                null,
                CREATED_AT,
                CREATED_AT,
                null,
                0
            )
        );

        Flow deployed = deploy(null);
        assertThrows(
            IllegalArgumentException.class,
            () -> rehydrate(
                deployed,
                false,
                ACTOR,
                CREATED_AT
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> rehydrate(deployed, true, null, null)
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
        boolean deleted,
        ActorRef deleter,
        Long deletedAt
    ) {
        return Flow.rehydrate(
            flow.id(),
            flow.companyId(),
            flow.key(),
            flow.reversion(),
            flow.description(),
            flow.inputs(),
            flow.outputs(),
            flow.tasks(),
            deleted,
            flow.creator(),
            flow.updater(),
            deleter,
            flow.createdAt(),
            flow.updatedAt(),
            deletedAt
        );
    }
}
