package org.flow.plugin.core.flow;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
public class SleepTest {

    @Test
    @ExecuteFlow("flows/valids/sleep-task-flow.yaml")
    void sleepTaskTest(Execution execution) {
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

}
