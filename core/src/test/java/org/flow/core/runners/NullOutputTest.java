package org.flow.core.runners;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.services.TaskOutputService;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
class NullOutputTest {
    @Inject
    private TaskOutputService taskOutputService;

    @Test
    @ExecuteFlow("flows/valids/null-output.yaml")
    void shouldIncludeNullOutput(Execution execution) throws org.flow.core.exceptions.InternalException {
        assertThat(execution).isNotNull();
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(taskOutputService.getOutputs(execution.getTaskRunList().getFirst())).hasSize(1);
        assertThat(taskOutputService.getOutputs(execution.getTaskRunList().getFirst()).containsKey("value")).isTrue();
    }
}
