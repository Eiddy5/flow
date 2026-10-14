package org.flow.core.tasks;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.State;
import org.flow.core.services.TaskOutputService;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
class OutputValuesTest {
    @Inject
    private TaskOutputService taskOutputService;

    @SuppressWarnings("unchecked")
    @Test
    @ExecuteFlow("flows/valids/output-values.yml")
    void output(Execution execution) throws Exception {
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getTaskRunList()).hasSize(2);

        TaskRun outputValues = execution.getTaskRunList().getFirst();
        // assertThat(outputValues.getOutputs()).isInstanceOf(Variables.InMemoryVariables.class);
        Map<String, Object> values = (Map<String, Object>) taskOutputService.getOutputs(outputValues).get("values");
        assertThat(values.get("output1")).isEqualTo("xyz");
        assertThat(values.get("output2")).isEqualTo("abc");

        outputValues = execution.getTaskRunList().getLast();
        // assertThat(outputValues.getOutputs()).isInstanceOf(Variables.InMemoryVariables.class);
        values = (Map<String, Object>) taskOutputService.getOutputs(outputValues).get("values");
        assertThat(values.get("output1")).isEqualTo("xyz");
        assertThat(values.get("output2")).isEqualTo("abc");
    }
}
