package org.flow.plugin.core.flow;

import java.util.List;

import org.flow.core.repositories.ExecutionRepositoryInterface;
import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.InternalException;
import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.services.ExecutionOutputService;

import jakarta.inject.Inject;

import static org.flow.core.utils.Rethrow.throwPredicate;
import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
class FlowOutputTest {
    @Inject
    private ExecutionOutputService executionOutputService;

    @Inject
    private ExecutionRepositoryInterface executionRepository;

    @Test
    @ExecuteFlow(value = "flows/valids/flow-with-outputs.yml", tenantId = "shouldgetsuccessexecutionforflowwithoutputs")
    void shouldGetSuccessExecutionForFlowWithOutputs(Execution execution) throws InternalException {
        assertThat(executionOutputService.getOutputs(execution)).hasSize(1);
        assertThat(executionOutputService.getOutputs(execution).get("key")).isEqualTo("{\"value\":\"flow-with-outputs\"}");
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Test
    @ExecuteFlow(value = "flows/valids/flow-with-optional-outputs.yml", tenantId = "shouldgetsuccessexecutionforflowwithoptionaloutputs")
    void shouldGetSuccessExecutionForFlowWithOptionalOutputs(Execution execution) throws InternalException {
        assertThat(executionOutputService.getOutputs(execution)).isNull();
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @SuppressWarnings("unchecked")
    @Test
    @ExecuteFlow(value = "flows/valids/flow-with-array-outputs.yml", tenantId = "shouldgetsuccessexecutionforflowwitharrayoutputs")
    void shouldGetSuccessExecutionForFlowWithArrayOutputs(Execution execution) throws InternalException {
        assertThat(executionOutputService.getOutputs(execution)).hasSize(1);
        assertThat((List<String>) executionOutputService.getOutputs(execution).get("myout")).contains("1rstValue", "2ndValue");
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Test
    @ExecuteFlow(value = "flows/valids/flow-with-outputs-failed.yml", tenantId = "shouldgetfailexecutionforflowwithinvalidoutputs")
    void shouldGetFailExecutionForFlowWithInvalidOutputs(Execution execution) throws InternalException {
        assertThat(executionOutputService.getOutputs(execution)).isNull();
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }

    @SuppressWarnings("deprecation")
    @Test
    @ExecuteFlow(value = "flows/valids/flow-with-outputs.yml", tenantId = "shouldnotstoreoutputsinsidetheexecution")
    void shouldNotStoreOutputsInsideTheExecution(Execution execution) {
        assertThat(execution.getOutputs()).isNull();
    }

    @Test
    @ExecuteFlow(value = "flows/valids/flow-with-outputs-with-loop.yml", tenantId = "loopexecutionsshouldnotcomputeoutputs")
    void loopExecutionsShouldNotComputeOutputs(Execution execution) throws InternalException {
        assertThat(executionOutputService.getOutputs(execution)).hasSize(1);

        // 2 loop sub-executions, one per iteration, all in SUCCESS without outputs
        List<Execution> subExecutions = executionRepository.findLoopSubExecutions(execution.getTenantId(), execution.getId(), null);
        assertThat(subExecutions).hasSize(2);
        assertThat(subExecutions).allMatch(e -> e.getState().getCurrent() == State.Type.SUCCESS);
        assertThat(subExecutions).allMatch(throwPredicate(e -> executionOutputService.getOutputs(e) == null));

    }
}
