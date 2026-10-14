package org.flow.plugin.core.http;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
public class RequestRunnerTest {
    @Test
    @ExecuteFlow("sanity-checks/request.yaml")
    void request(Execution execution) {
        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Test
    @ExecuteFlow("sanity-checks/request_no_options.yaml")
    void request_no_options(Execution execution) {
        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Disabled("The remote page didn't work anymore")
    @Test
    @ExecuteFlow("sanity-checks/request-basicauth.yaml")
    void requestBasicAuth(Execution execution) {
        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }
}
