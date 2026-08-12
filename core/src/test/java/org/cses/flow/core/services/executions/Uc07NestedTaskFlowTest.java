package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-07 用户处理多阶段外派流程.md
 */
class Uc07NestedTaskFlowTest {

    @Test
    void s3RejectsDuplicateKeyAcrossNestedBranchesAtomically() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            FlowDraft draft = fixture.flowService().saveDraft(
                fixture.session(),
                NestedPauseResumeIntegrationTest.duplicateNestedKeyYaml(
                    "uc07-s3-flow"
                )
            );
            long lockVersion = draft.lockVersion();
            int executionCount = fixture.executionService().executions(
                fixture.session()
            ).size();

            WorkflowException exception = assertThrows(
                WorkflowException.class,
                () -> fixture.flowService().deploy(
                    fixture.session(),
                    draft.id()
                )
            );
            assertTrue(exception.getMessage().contains(
                "Duplicate Task key: backend-review"
            ));

            FlowDraft reloaded = fixture.flowService().draft(
                fixture.session(),
                draft.id()
            ).orElseThrow();

            assertEquals(lockVersion, reloaded.lockVersion());
            assertEquals(draft.raw(), reloaded.raw());
            assertTrue(fixture.flowService().flow(
                fixture.session(),
                draft.id(),
                1L
            ).isEmpty());
            assertEquals(
                executionCount,
                fixture.executionService().executions(fixture.session()).size()
            );
        }
    }
}
