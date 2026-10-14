package org.flow.plugin.core.execution;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.services.TaskOutputService;
import org.flow.core.utils.IdUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
class SetVariablesTest {
    @Inject
    private TaskOutputService taskOutputService;

    @Inject
    private RunContextFactory runContextFactory;

    @ExecuteFlow("flows/valids/set-variables.yaml")
    @Test
    void shouldUpdateExecution(Execution execution) throws org.flow.core.exceptions.InternalException {
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(((Map<String, Object>) taskOutputService.getOutputs(execution.getTaskRunList().get(1)).get("values"))).containsEntry("message", "Hello Loïc");
    }

    @ExecuteFlow("flows/valids/set-variables-duplicate.yaml")
    @Test
    void shouldFailWhenExistingVariable(Execution execution) {
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(execution.getTaskRunList().getFirst().getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }

    @ExecuteFlow("flows/valids/set-variables-allow-failure.yaml")
    @Test
    void shouldWarnAndContinueWhenAllowFailureIsSet(Execution execution) {
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.WARNING);
        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("set-vars").getFirst().getState().getCurrent()).isEqualTo(State.Type.WARNING);
        assertThat(execution.findTaskRunsByTaskId("after").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @ExecuteFlow("flows/valids/set-variables-allow-warning.yaml")
    @Test
    void shouldSucceedWhenAllowFailureAndAllowWarningAreSet(Execution execution) {
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("set-vars").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.findTaskRunsByTaskId("after").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Test
    void shouldRenderVariablesForEachExecution() throws Exception {
        // the executor calls update() on the task instance of its cached flow, so the same task
        // instance is reused by every execution of the flow: it must not reuse the first rendering
        SetVariables task = JacksonMapper.ofJson().readValue(
            """
                {
                  "id": "set",
                  "type": "org.flow.plugin.core.execution.SetVariables",
                  "variables": {"isLts": "{{ inputs.version | startsWith('1.3.') }}"}
                }""",
            SetVariables.class
        );

        Execution first = task.update(execution(), runContextFactory.of(Map.of("inputs", Map.of("version", "1.3.9"))));
        assertThat(first.getVariables()).containsEntry("isLts", "true");

        Execution second = task.update(execution(), runContextFactory.of(Map.of("inputs", Map.of("version", "1.0.56"))));
        assertThat(second.getVariables()).containsEntry("isLts", "false");
    }

    private Execution execution() {
        return Execution.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unittest")
            .flowId("set-variables")
            .flowRevision(1)
            .state(new State().withState(State.Type.RUNNING))
            .build();
    }
}
