package org.flow.core.runners;

import java.time.ZonedDateTime;
import java.util.Optional;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.State;
import org.flow.core.models.flows.State.Type;
import org.flow.core.queues.QueueException;
import org.flow.core.repositories.FlowRepositoryInterface;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import static org.assertj.core.api.Assertions.assertThat;

@Singleton
public class ScheduleDateCaseTest {
    @Inject
    private FlowRepositoryInterface flowRepository;
    @Inject
    private TestRunnerUtils runnerUtils;

    public void shouldScheduleOnDate(String tenantId) throws QueueException {
        ZonedDateTime scheduleOn = ZonedDateTime.now().plusSeconds(1);
        Flow flow = flowRepository.findById(tenantId, "org.flow.tests", "minimal").orElseThrow();
        Execution execution = Execution.newExecution(flow, null, null, Optional.of(scheduleOn));
        assertThat(execution.getScheduleDate()).isEqualTo(scheduleOn.toInstant());
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.CREATED);

        runnerUtils.emitAndAwaitExecution(e -> e.getState().getCurrent().equals(Type.SUCCESS), execution);
    }
}
