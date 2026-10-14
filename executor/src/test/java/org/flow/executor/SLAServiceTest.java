package org.flow.executor;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.State;
import org.flow.core.models.flows.sla.SLA;
import org.flow.core.models.flows.sla.Violation;
import org.flow.core.models.flows.sla.types.ExecutionAssertionSLA;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.utils.IdUtils;
import org.flow.plugin.core.log.Log;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest
class SLAServiceTest {
    @Inject
    private SLAService slaService;

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldReturnViolationWithDeclaredBehaviorWhenAssertionCannotBeEvaluated() {
        SLA sla = ExecutionAssertionSLA.builder()
            .id("broken")
            .type(SLA.Type.EXECUTION_ASSERTION)
            .behavior(SLA.Behavior.FAIL)
            ._assert("{{ nope.missing.thing }}")
            .build();
        Flow flow = Flow.builder()
            .tenantId("tenant")
            .namespace("org.flow.unit-test")
            .id(IdUtils.create())
            .tasks(List.of(Log.builder().id("t").type(Log.class.getName()).message("hi").build()))
            .sla(List.of(sla))
            .build();
        Execution execution = Execution.builder()
            .tenantId("tenant")
            .id(IdUtils.create())
            .namespace(flow.getNamespace())
            .flowId(flow.getId())
            .flowRevision(1)
            .state(new State())
            .build();
        RunContext runContext = runContextFactory.of(flow, execution);

        List<Violation> violations = slaService.evaluateExecutionChangedSLA(runContext, flow, execution);

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst().behavior()).isEqualTo(SLA.Behavior.FAIL);
        assertThat(violations.getFirst().reason()).contains("could not be evaluated");
    }
}
