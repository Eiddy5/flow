package org.flow.plugin.core.flow;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.repositories.ExecutionRepositoryInterface;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.utils.Await;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
public class BadFlowableTest {
    @Inject
    private ExecutionRepositoryInterface executionRepository;

    @Test
    @ExecuteFlow(value = "flows/valids/flowable-fail.yaml", tenantId = "sequential")
    void sequential(Execution execution) {
        assertThat(execution.getTaskRunList().size()).as("Task runs were: \n" + JacksonMapper.log(execution.getTaskRunList())).isGreaterThanOrEqualTo(2);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(execution.getTaskRunList().getFirst().getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(execution.getTaskRunList().getFirst().getAttempts().getFirst().getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }

    @Test
    @ExecuteFlow(value = "flows/valids/flowable-with-parent-fail.yaml", tenantId = "flowablewithparentfail")
    void flowableWithParentFail(Execution execution) throws TimeoutException {
        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);

        // The parent terminates when the first sub-execution fails, but concurrent sub-executions
        // may still be running. Wait for all to reach a terminal state before asserting.
        Await.until(
            () -> executionRepository.findLoopSubExecutions(execution.getTenantId(), execution.getId(), null).stream().allMatch(e -> e.getState().isTerminated()),
            Duration.ofMillis(100),
            Duration.ofSeconds(30)
        );
        var subExecutions = executionRepository.findLoopSubExecutions(execution.getTenantId(), execution.getId(), null);
        assertThat(subExecutions).hasSize(2);
        assertThat(subExecutions).extracting("state.current").containsOnly(State.Type.FAILED);
    }
}
