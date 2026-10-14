package org.flow.core.models.flows.input;

import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.InputOutputValidationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.FlowInputOutput;
import org.flow.core.runners.TestRunnerUtils;

import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@FlowTest(startRunner = true)
class JsonInputSchemaFlowIntegrationTest {
    @Inject
    private TestRunnerUtils runnerUtils;

    @Inject
    private FlowInputOutput flowIO;

    @Test
    @LoadFlows("flows/valids/json-input-schema-validation.yaml")
    void shouldRunFlowWhenJsonInputMatchesSchema() throws TimeoutException, QueueException, org.flow.core.exceptions.InternalException {
        // Given
        Map<String, Object> inputs = Map.of(
            "jsonWithSchema", Map.of("name", "flow"),
            "jsonWithoutSchema", Map.of("any", "shape")
        );

        // When
        Execution execution = runnerUtils.runOne(
            MAIN_TENANT,
            "org.flow.tests",
            "json-input-schema-validation",
            null,
            (flow, execution1) -> flowIO.readExecutionInputs(flow, execution1, inputs)
        );

        // Then
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getTaskRunList()).hasSize(2);
    }

    @Test
    @LoadFlows("flows/valids/json-input-schema-validation.yaml")
    void shouldFailWhenJsonInputDoesNotMatchSchema() {
        // Given
        Map<String, Object> inputs = Map.of(
            "jsonWithSchema", Map.of("name", 42),
            "jsonWithoutSchema", Map.of("ok", true)
        );

        // When
        InputOutputValidationException exception = assertThrows(
            InputOutputValidationException.class,
            () -> runnerUtils.runOne(
                MAIN_TENANT,
                "org.flow.tests",
                "json-input-schema-validation",
                null,
                (flow, execution1) -> flowIO.readExecutionInputs(flow, execution1, inputs)
            )
        );

        // Then
        assertThat(exception.getMessage()).contains("Invalid value for input `jsonWithSchema`");
        assertThat(exception.getMessage()).contains("it must match the json schema");
    }
}
