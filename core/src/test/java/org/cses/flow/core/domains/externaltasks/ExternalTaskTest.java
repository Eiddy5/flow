package org.cses.flow.core.domains.externaltasks;

import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalTaskTest {

    @Test
    void completesValidatedWaitWithoutOwningTaskDefinition() {
        ExternalTask externalTask = ExternalTask.create(
            "external-task-company",
            "execution-1",
            "task-run-1"
        );

        assertFalse(externalTask.id().isBlank());
        assertEquals(ExternalTaskStatus.WAITING, externalTask.status());
        assertTrue(externalTask.outputs().isEmpty());
        assertEquals(0, externalTask.lockVersion());
        assertThrows(
            NullPointerException.class,
            () -> externalTask.complete(null)
        );
        assertEquals(ExternalTaskStatus.WAITING, externalTask.status());

        externalTask.complete(Map.of("decision", "approved"));

        assertEquals(ExternalTaskStatus.COMPLETED, externalTask.status());
        assertEquals(
            Map.of("decision", "approved"),
            externalTask.outputs()
        );
        assertEquals(1, externalTask.lockVersion());
        assertThrows(
            WorkflowException.class,
            () -> externalTask.complete(Map.of("decision", "approved"))
        );
    }

    @Test
    void cancelEndsWaitingWithoutOutputs() {
        ExternalTask externalTask = ExternalTask.create(
            "external-task-company",
            "execution-1",
            "task-run-1"
        );

        externalTask.cancel();

        assertEquals(ExternalTaskStatus.CANCELED, externalTask.status());
        assertTrue(externalTask.outputs().isEmpty());
        assertEquals(1, externalTask.lockVersion());
        assertThrows(
            WorkflowException.class,
            () -> externalTask.complete(Map.of())
        );
    }

    @Test
    void rehydrateRejectsOutputsOnAnActiveWait() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ExternalTask.rehydrate(
                "external-task-1",
                "external-task-company",
                "execution-1",
                "task-run-1",
                ExternalTaskStatus.WAITING,
                Map.of("decision", "approved"),
                0
            )
        );
    }
}
