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

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
public class TaskWithAllowWarningTest {
    @Inject
    private TestRunnerUtils runnerUtils;

    @Test
    @ExecuteFlow("flows/valids/task-allow-warning-runnable.yml")
    void runnableTask(Execution execution) {
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("fail").getFirst().getAttempts().size()).isEqualTo(3);
    }

    @Test
    @LoadFlows(
        { "flows/valids/task-allow-warning-executable-flow.yml",
            "flows/valids/failing-subflow.yaml" }
    )
    void executableTask_Flow() throws QueueException, TimeoutException {
        Execution execution = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "task-allow-warning-executable-flow");
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getTaskRunList()).hasSize(2);
    }

    @Test
    @ExecuteFlow("flows/valids/task-allow-warning-flowable.yml")
    void flowableTask(Execution execution) {
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getTaskRunList()).hasSize(3);
    }
}
