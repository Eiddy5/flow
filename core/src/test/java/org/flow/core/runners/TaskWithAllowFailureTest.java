package org.flow.core.runners;

import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.queues.QueueException;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
public class TaskWithAllowFailureTest {
    @Inject
    private TestRunnerUtils runnerUtils;

    @Test
    @ExecuteFlow("flows/valids/task-allow-failure-runnable.yml")
    void runnableTask(Execution execution) {
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.WARNING);
        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("fail").getFirst().getAttempts().size()).isEqualTo(3);
    }

    @Test
    @LoadFlows(
        value = { "flows/valids/task-allow-failure-executable-flow.yml",
            "flows/valids/failing-subflow.yaml" },
        tenantId = "tenant1"
    )
    void executableTask_Flow() throws QueueException, TimeoutException {
        Execution execution = runnerUtils.runOne("tenant1", "org.flow.tests", "task-allow-failure-executable-flow");
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.WARNING);
        assertThat(execution.getTaskRunList()).hasSize(2);
    }

    @Test
    @ExecuteFlow("flows/valids/task-allow-failure-flowable.yml")
    void flowableTask(Execution execution) {
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.WARNING);
        assertThat(execution.getTaskRunList()).hasSize(3);
    }
}
