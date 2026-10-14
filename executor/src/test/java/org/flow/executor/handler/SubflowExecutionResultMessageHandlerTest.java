package org.flow.executor.handler;

import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.killswitch.EvaluationType;
import org.flow.core.killswitch.KillSwitchService;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.models.flows.State;
import org.flow.core.repositories.ExecutionRepositoryInterface;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.runners.SubflowExecutionResult;
import org.flow.executor.ExecutorContext;

import io.micronaut.test.annotation.MockBean;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@FlowTest
class SubflowExecutionResultMessageHandlerTest {
    @Inject
    private SubflowExecutionResultMessageHandler subflowExecutionResultMessageHandler;

    @Inject
    private ExecutionRepositoryInterface executionRepository;

    @Inject
    private FlowRepositoryInterface flowRepository;

    @Inject
    KillSwitchService killSwitchService;

    @MockBean(KillSwitchService.class)
    KillSwitchService killSwitchService() {
        return mock(KillSwitchService.class);
    }

    @BeforeEach
    void setUp() {
        when(killSwitchService.evaluate(anyString())).thenReturn(EvaluationType.PASS);
        when(killSwitchService.evaluate(any(TaskRun.class))).thenReturn(EvaluationType.PASS);
    }

    @Test
    void shouldReturnEmptyForNonExistingExecution() {
        var subflowExecutionResult = SubflowExecutionResult.builder()
            .executionId("execution")
            .state(State.Type.SUCCESS)
            .parentTaskRun(
                TaskRun.builder()
                    .id("parent")
                    .flowId("flow")
                    .namespace("namespace")
                    .executionId("execution")
                    .build()
            )
            .build();

        var maybeExecutor = subflowExecutionResultMessageHandler.handle(subflowExecutionResult);

        assertThat(maybeExecutor).isEmpty();
    }

    @Test
    void shouldReturnAnExecutorForExistingExecution() {
        var flow = flowRepository.create(GenericFlow.of(Fixtures.flow()));
        var parentExecution = Execution.newExecution(flow, Collections.emptyList());
        executionRepository.save(parentExecution);
        var execution = Execution.newExecution(flow, Collections.emptyList());
        executionRepository.save(execution);
        var subflowExecutionResult = SubflowExecutionResult.builder()
            .executionId(execution.getId())
            .state(State.Type.SUCCESS)
            .parentTaskRun(
                TaskRun.builder()
                    .id("parent")
                    .flowId(parentExecution.getFlowId())
                    .namespace(parentExecution.getNamespace())
                    .executionId(parentExecution.getId())
                    .build()
            )
            .build();

        var maybeExecutor = subflowExecutionResultMessageHandler.handle(subflowExecutionResult);

        assertThat(maybeExecutor).isPresent();
    }

    @Test
    void shouldReturnEmptyWhenChildExecutionKillSwitched() {
        // Given — child execution is kill-switched
        var parentTaskRun = TaskRun.builder()
            .id("taskrun-1").executionId("parent-exec-1").taskId("task-1")
            .state(new State().withState(State.Type.RUNNING)).build();
        var message = SubflowExecutionResult.builder()
            .executionId("child-exec-1").parentTaskRun(parentTaskRun).build();
        when(killSwitchService.evaluate("child-exec-1")).thenReturn(EvaluationType.IGNORE);

        // When
        Optional<ExecutorContext> result = subflowExecutionResultMessageHandler.handle(message);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenParentTaskRunKillSwitched() {
        // Given — child passes but parent task run is kill-switched
        var parentTaskRun = TaskRun.builder()
            .id("taskrun-1").executionId("parent-exec-1").taskId("task-1")
            .state(new State().withState(State.Type.RUNNING)).build();
        var message = SubflowExecutionResult.builder()
            .executionId("child-exec-1").parentTaskRun(parentTaskRun).build();
        when(killSwitchService.evaluate(any(TaskRun.class))).thenReturn(EvaluationType.IGNORE);

        // When
        Optional<ExecutorContext> result = subflowExecutionResultMessageHandler.handle(message);

        // Then
        assertThat(result).isEmpty();
    }
}
