package org.flow.plugin.core.flow;

import java.util.Optional;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.models.Label;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.TestRunnerUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
class CorrelationIdTest {
    @Inject
    private TestRunnerUtils runnerUtils;

    @Test
    @LoadFlows(
        value = { "flows/valids/subflow-parent.yaml",
            "flows/valids/subflow-child.yaml",
            "flows/valids/subflow-grand-child.yaml" },
        tenantId = "shouldhavecorrelationid"
    )
    void shouldHaveCorrelationId() throws QueueException, TimeoutException {
        Execution execution = runnerUtils.runOne("shouldhavecorrelationid", "org.flow.tests", "subflow-parent");
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        Execution child = runnerUtils.awaitFlowExecution(
            e -> e.getState().getCurrent().isTerminated(), "shouldhavecorrelationid",
            "org.flow.tests", "subflow-child"
        );

        assertThat(child.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        Optional<Label> correlationId = child.getLabels().stream().filter(label -> label.key().equals(Label.CORRELATION_ID)).findAny();
        assertThat(correlationId.isPresent()).isTrue();
        assertThat(correlationId.get().value()).isEqualTo(execution.getId());

        Execution grandChild = runnerUtils.awaitFlowExecution(
            e -> e.getState().getCurrent().isTerminated(), "shouldhavecorrelationid",
            "org.flow.tests", "subflow-grand-child"
        );
        assertThat(grandChild.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        correlationId = grandChild.getLabels().stream().filter(label -> label.key().equals(Label.CORRELATION_ID)).findAny();
        assertThat(correlationId.isPresent()).isTrue();
        assertThat(correlationId.get().value()).isEqualTo(execution.getId());
    }
}
