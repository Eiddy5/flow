package org.flow.core.runners;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.State;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.services.ExecutionService;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.test.annotation.MockBean;
import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@FlowTest(startRunner = true)
class PausedTaskNotifierTest {
    @Inject
    TestRunnerUtils runnerUtils;

    @Inject
    ExecutionService executionService;

    @Inject
    FlowRepositoryInterface flowRepository;

    @Inject
    PausedTaskNotifier pausedTaskNotifier;

    @MockBean
    @Replaces(PausedTaskNotifier.NoopPausedTaskNotifier.class)
    PausedTaskNotifier pausedTaskNotifier() {
        return mock(PausedTaskNotifier.class);
    }

    @Test
    @LoadFlows({ "flows/valids/pause-test.yaml" })
    void notifiesExactlyOnceOnPause() throws Exception {
        Execution execution = runnerUtils.runOneUntilPaused(MAIN_TENANT, "org.flow.tests", "pause-test", null, null, Duration.ofSeconds(30));
        String executionId = execution.getId();
        Flow flow = flowRepository.findByExecution(execution);

        verify(pausedTaskNotifier, times(1)).taskPaused(any(), any(), any(), any());

        Execution restarted = executionService.markAs(
            execution,
            flow,
            execution.findTaskRunByTaskIdAndValue("pause", java.util.List.of()).getId(),
            State.Type.RUNNING
        );

        runnerUtils.emitAndAwaitExecution(
            e -> e.getId().equals(executionId) && e.getState().getCurrent() == State.Type.SUCCESS,
            restarted
        );

        // resuming must not trigger a second notification
        verify(pausedTaskNotifier, times(1)).taskPaused(any(), any(), any(), any());
    }

    @Test
    @LoadFlows({ "flows/valids/pause-test.yaml" })
    void reachesPausedEvenWhenNotifierThrows() throws Exception {
        doThrow(new RuntimeException("notifier boom")).when(pausedTaskNotifier).taskPaused(any(), any(), any(), any());

        Execution execution = runnerUtils.runOneUntilPaused(MAIN_TENANT, "org.flow.tests", "pause-test", null, null, Duration.ofSeconds(30));

        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.PAUSED);
    }
}
