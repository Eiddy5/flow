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
class RunnableTaskExceptionTest {
    @Inject
    private TaskOutputService taskOutputService;

    @Test
    @ExecuteFlow("flows/valids/exception-with-output.yaml")
    void simple(Execution execution) throws org.flow.core.exceptions.InternalException {
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(taskOutputService.getOutputs(execution.getTaskRunList().get(0)).get("message")).isEqualTo("Oh no!");
    }
}