package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-06 用户使用 Pause 结果选择 Flow 路径.md
 */
class Uc06ConditionalRouteTest {

    @Test
    void s4RejectsInvalidRouteWhenDeployingFlow() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow draft = fixture.flowService().save(
                fixture.session(),
                PublishFlowCommand.from(routeYaml(
                    "uc06-s4-flow",
                    "{{ outputs.decision }} =="
                ))
            );
            IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.flowService().save(
                    fixture.session(),
                    PublishFlowCommand.from(draft.key(), false)
                )
            );
            assertTrue(failure.getMessage().contains("Condition"));
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

    private static String routeYaml(String key, String approveExpress) {
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
                type: org.cses.flow.extensions.flow.Route
                route: '%s'
                tasks:
                  - key: approve
                    type: org.cses.flow.extensions.tasks.AutomaticTask
              - key: reject-decision
                type: org.cses.flow.extensions.flow.Route
                route: '{{ outputs.approval-decision.decision }} == REJECTED'
                tasks:
                  - key: reject
                    type: org.cses.flow.extensions.tasks.AutomaticTask
            """.formatted(key, approveExpress);
    }
}
