package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
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
            Flow draft = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(
                    NestedPauseResumeIntegrationTest.duplicateNestedKeyYaml(
                    "uc07-s3-flow"
                    )
                )
            );
            int executionCount = fixture.executionService().executions(
                fixture.session()
            ).size();

            WorkflowException exception = assertThrows(
                WorkflowException.class,
                () -> fixture.flowService().save(
                    fixture.session(),
                    PublishFlowCommand.from(draft.key(), false)
                )
            );
            assertTrue(exception.getMessage().contains(
                "Duplicate Task key: backend-review"
            ));

            Flow reloaded = fixture.flowService().draft(
                fixture.session(),
                draft.key()
            ).orElseThrow();

            assertEquals(draft.source(), reloaded.source());
            assertTrue(fixture.flowService().flow(
                fixture.session(),
                draft.key(),
                1L
            ).isEmpty());
            assertEquals(
                executionCount,
                fixture.executionService().executions(fixture.session()).size()
            );
        }
    }
}
