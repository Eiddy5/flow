package org.flow.plugin.core.trigger;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.EvaluateTrigger;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@FlowTest
class PollingTest {

    @Test
    @EvaluateTrigger(
        flow = "flows/tests/trigger-polling.yaml",
        triggerId = "polling-trigger-1"
    )
    void pollingTriggerSuccess(Optional<Execution> optionalExecution) {
        assertThat(optionalExecution).isPresent();
        Execution execution = optionalExecution.get();
        assertThat(execution.getFlowId()).isEqualTo("polling-flow");
        assertTrue(execution.getState().getCurrent().isCreated());
    }
}
