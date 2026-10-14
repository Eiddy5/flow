package org.flow.plugin.core.execution;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.TestRunnerUtils;

import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
public class FailTest {

    @Inject
    private TestRunnerUtils runnerUtils;

    @Test
    @LoadFlows({ "flows/valids/fail-on-switch.yaml" })
    void failOnSwitch() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            MAIN_TENANT, "org.flow.tests", "fail-on-switch", null,
            (f, e) -> Map.of("param", "fail"), Duration.ofSeconds(20)
        );

        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(execution.findTaskRunsByTaskId("switch").getFirst().getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }

    @Test
    @LoadFlows(value = { "flows/valids/fail-on-condition.yaml" }, tenantId = "fail")
    void failOnCondition() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            "fail", "org.flow.tests", "fail-on-condition", null,
            (f, e) -> Map.of("param", "fail"), Duration.ofSeconds(20)
        );

        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("fail").getFirst().getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }

    @Test
    @LoadFlows(value = { "flows/valids/fail-on-condition.yaml" }, tenantId = "success")
    void dontFailOnCondition() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            "success", "org.flow.tests", "fail-on-condition", null,
            (f, e) -> Map.of("param", "success"), Duration.ofSeconds(20)
        );

        assertThat(execution.getTaskRunList()).hasSize(3);
        assertThat(execution.findTaskRunsByTaskId("fail").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }
}
