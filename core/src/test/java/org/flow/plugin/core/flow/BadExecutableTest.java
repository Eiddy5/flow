package org.flow.plugin.core.flow;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
public class BadExecutableTest {

    @Test
    @ExecuteFlow(value = "flows/valids/executable-fail.yml", tenantId = "badexecutable")
    void badExecutable(Execution execution) {
        assertThat(execution.getTaskRunList().size()).isEqualTo(1);
        assertThat(execution.getTaskRunList().getFirst().getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }
}
