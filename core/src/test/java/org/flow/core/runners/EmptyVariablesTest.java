package org.flow.core.runners;

import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.queues.QueueException;

import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
public class EmptyVariablesTest {

    @Inject
    private TestRunnerUtils runnerUtils;
    @Inject
    private FlowInputOutput flowIO;

    @Test
    @LoadFlows({ "flows/valids/empty-variables.yml" })
    void emptyVariables() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            MAIN_TENANT,
            "org.flow.tests",
            "empty-variables",
            null,
            (flow, exec) -> flowIO.readExecutionInputs(flow, exec, Map.of("emptyKey", "{ \"foo\": \"\" }", "emptySubObject", "{\"json\":{\"someEmptyObject\":{}}}"))
        );

        assertThat(execution).isNotNull();
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getTaskRunList()).hasSize(3);
    }
}
