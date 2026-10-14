package org.flow.core.runners;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.flow.core.executor.command.Create;
import org.flow.core.executor.command.ExecutionCommand;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionId;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.State;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.repositories.ExecutionRepositoryInterface;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.services.IgnoreExecutionService;
import org.flow.core.utils.Await;
import org.flow.core.utils.IdUtils;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

@Singleton
public class IgnoreExecutionCaseTest {
    @Inject
    protected DispatchQueueInterface<ExecutionCommand> executionCommandQueue;

    @Inject
    protected TestRunnerUtils runnerUtils;

    @Inject
    private ExecutionRepositoryInterface executionRepository;

    @Inject
    private IgnoreExecutionService ignoreExecutionService;

    @Inject
    private FlowRepositoryInterface flowRepository;

    public void shouldIgnoreExecutionById() throws TimeoutException, QueueException {
        Flow flow = flowRepository.findById(MAIN_TENANT, "org.flow.tests", "minimal").orElseThrow();
        String execution1Id = IdUtils.create();
        ignoreExecutionService.setIgnoredExecutions(List.of(execution1Id));

        executionCommandQueue.emit(Create.of(new ExecutionId(flow.getTenantId(), flow.getNamespace(), flow.getId(), execution1Id, flow.getRevision())));
        Execution execution2 = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "minimal");

        // the execution 2 should be in success and the 1 still created
        assertThat(execution2.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        Execution execution1 = Await.until(() -> executionRepository.findById(MAIN_TENANT, execution1Id).orElse(null), Duration.ofMillis(100), Duration.ofSeconds(1));
        assertThat(execution1.getState().getCurrent()).isEqualTo(State.Type.CREATED);
    }

    public void shouldIgnoreExecutionByFlowId() throws TimeoutException, QueueException {
        Flow flow = flowRepository.findById(MAIN_TENANT, "org.flow.tests", "output-values").orElseThrow();
        String execution1Id = IdUtils.create();
        ignoreExecutionService.setIgnoredFlows(List.of(MAIN_TENANT + "|" + "org.flow.tests" + "|" + "output-values"));

        executionCommandQueue.emit(Create.of(new ExecutionId(flow.getTenantId(), flow.getNamespace(), flow.getId(), execution1Id, flow.getRevision())));
        Execution execution2 = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "minimal");

        // the execution 2 should be in success and the 1 still created
        assertThat(execution2.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        Execution execution1 = Await.until(() -> executionRepository.findById(MAIN_TENANT, execution1Id).orElse(null), Duration.ofMillis(100), Duration.ofSeconds(1));
        assertThat(execution1.getState().getCurrent()).isEqualTo(State.Type.CREATED);
    }

    public void shouldIgnoreExecutionByNamespace() throws TimeoutException, QueueException {
        Flow flow = flowRepository.findById(MAIN_TENANT, "org.flow.tests2", "minimal").orElseThrow();
        String execution1Id = IdUtils.create();
        ignoreExecutionService.setIgnoredNamespaces(List.of(MAIN_TENANT + "|" + "org.flow.tests2"));

        executionCommandQueue.emit(Create.of(new ExecutionId(flow.getTenantId(), flow.getNamespace(), flow.getId(), execution1Id, flow.getRevision())));
        Execution execution2 = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "minimal");

        // the execution 2 should be in success and the 1 still created
        assertThat(execution2.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        Execution execution1 = Await.until(() -> executionRepository.findById(MAIN_TENANT, execution1Id).orElse(null), Duration.ofMillis(100), Duration.ofSeconds(1));
        assertThat(execution1.getState().getCurrent()).isEqualTo(State.Type.CREATED);
    }
}
