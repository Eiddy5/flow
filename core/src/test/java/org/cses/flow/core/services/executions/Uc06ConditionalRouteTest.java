package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-06 用户提交外派结果后的条件路径.md
 */
class Uc06ConditionalRouteTest {

    @Test
    void s4RejectsInvalidRouteWhenDeployingFlow() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow draft = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(routeYaml(
                    "uc06-s4-flow",
                    "outputs.decision =="
                ))
            );
            IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.flowService().save(
                    fixture.session(),
                    PublishFlowCommand.from(draft.key(), false)
                )
            );
            assertTrue(failure.getMessage().contains("route expression"));
            assertTrue(
                fixture.flowService()
                    .latestFlow(fixture.session(), draft.key())
                    .isEmpty()
            );
            assertTrue(
                fixture.executionService().executions(fixture.session())
                    .isEmpty()
            );
        }
    }

    private static String routeYaml(String key, String approveRoute) {
        return """
            key: %s
            description: conditional approval
            tasks:
              - key: approval-decision
                type: org.cses.flow.extensions.flow.Pause
                pause:
                  key: create-approval-decision
                  type: org.cses.flow.extensions.tasks.AutomaticTask
                resume:
                  - key: decision
                    type: STRING
                outputs:
                  - key: decision
                    type: STRING
              - key: route-decision
                type: org.cses.flow.core.services.executions.ConditionalRouteResumeIntegrationTest.ResumeDecisionTask
                dependOn:
                  - approval-decision
                outputs:
                  - key: decision
                    type: STRING
                tasks:
                  - key: approve
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                    route: '%s'
                  - key: reject
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                    route: 'outputs.decision == "REJECTED"'
            """.formatted(key, approveRoute);
    }
}
