package org.flow.plugin.core.flow;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.metrics.MetricRegistry;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRunAttempt;
import org.flow.core.models.flows.State;
import org.flow.core.queues.QueueException;
import org.flow.core.repositories.ExecutionRepositoryInterface;
import org.flow.core.runners.TestRunnerUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
class IfTest {

    @Inject
    private TestRunnerUtils runnerUtils;

    @Inject
    private ExecutionRepositoryInterface executionRepository;

    @Inject
    private MetricRegistry metricRegistry;

    @Test
    @LoadFlows(value = { "flows/valids/if-condition.yaml" }, tenantId = "iftruthy")
    void ifTruthy() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            "iftruthy", "org.flow.tests", "if-condition", null,
            (f, e) -> Map.of("param", true), Duration.ofSeconds(120)
        );
        List<TaskRunAttempt> flowableAttempts = execution.findTaskRunsByTaskId("if").getFirst().getAttempts();

        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("when-true").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        assertThat(flowableAttempts).isNotNull();
        assertThat(flowableAttempts.getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        execution = runnerUtils.runOne(
            "iftruthy", "org.flow.tests", "if-condition", null,
            (f, e) -> Map.of("param", "true"), Duration.ofSeconds(120)
        );

        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("when-true").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        execution = runnerUtils.runOne(
            "iftruthy", "org.flow.tests", "if-condition", null,
            (f, e) -> Map.of("param", 1), Duration.ofSeconds(120)
        );

        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("when-true").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Test
    @LoadFlows(value = { "flows/valids/if-condition.yaml" }, tenantId = "iffalsy")
    void ifFalsy() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            "iffalsy", "org.flow.tests", "if-condition", null,
            (f, e) -> Map.of("param", false), Duration.ofSeconds(120)
        );

        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("when-false").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        execution = runnerUtils.runOne(
            "iffalsy", "org.flow.tests", "if-condition", null,
            (f, e) -> Map.of("param", "false"), Duration.ofSeconds(120)
        );

        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("when-false").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        execution = runnerUtils.runOne(
            "iffalsy", "org.flow.tests", "if-condition", null,
            (f, e) -> Map.of("param", 0), Duration.ofSeconds(120)
        );

        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("when-false").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        execution = runnerUtils.runOne(
            "iffalsy", "org.flow.tests", "if-condition", null,
            (f, e) -> Map.of("param", -0), Duration.ofSeconds(120)
        );

        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("when-false").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        // We cannot test null as inputs cannot be null
    }

    @Test
    @LoadFlows(value = { "flows/valids/if-without-else.yaml" }, tenantId = "ifwithoutelse")
    void ifWithoutElse() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            "ifwithoutelse", "org.flow.tests", "if-without-else", null,
            (f, e) -> Map.of("param", true), Duration.ofSeconds(120)
        );

        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.findTaskRunsByTaskId("when-true").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        execution = runnerUtils.runOne(
            "ifwithoutelse", "org.flow.tests", "if-without-else", null,
            (f, e) -> Map.of("param", false), Duration.ofSeconds(120)
        );
        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(execution.findTaskRunsByTaskId("when-true").isEmpty()).isTrue();
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Test
    @LoadFlows(value = { "flows/valids/if-in-flowable.yaml" }, tenantId = "ifinflowable")
    void ifInFlowable() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(
            "ifinflowable", "org.flow.tests", "if-in-flowable", null,
            (f, e) -> Map.of("param", true), Duration.ofSeconds(120)
        );

        assertThat(execution.getTaskRunList()).hasSize(1);

        var subExecutions = executionRepository.findLoopSubExecutions(execution.getTenantId(), execution.getId(), null);
        assertThat(subExecutions.size()).isEqualTo(3);
        assertThat(subExecutions.get(1).findTaskRunsByTaskId("after_if").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(subExecutions.getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Test
    @ExecuteFlow(value = "flows/valids/if-with-only-disabled-tasks.yaml", tenantId = "ifwithonlydisabledtasks")
    void ifWithOnlyDisabledTasks(Execution execution) {
        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(execution.findTaskRunsByTaskId("if").getFirst().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Test
    @ExecuteFlow(value = "flows/valids/if-in-parallel.yaml", tenantId = "ifonparallelbranch")
    void ifOnParallelBranch(Execution execution) {
        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        var subExecutions = executionRepository.findLoopSubExecutions(execution.getTenantId(), execution.getId(), null);
        assertThat(subExecutions.size()).isEqualTo(3);
    }

    @Test
    @ExecuteFlow(value = "flows/valids/if-condition-fail.yaml", tenantId = "ifconditionfail")
    void ifConditionFail(Execution execution) {
        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(execution.getTaskRunList().getFirst().getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(execution.getTaskRunList().getFirst().getAttempts().getFirst().getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }

    // upstream issue #9008: the Executor used to call resolveNexts() on a
    // running If for every executor cycle, most of them resolving to nothing. Pin the number of
    // resolutions so a regression shows up here rather than only in a profiler.
    @Test
    @LoadFlows(value = { "flows/valids/if-condition.yaml" }, tenantId = "ifflowablecount")
    void shouldResolveFlowableOnlyWhenItCanProgress() throws TimeoutException, QueueException {
        // Given
        double before = flowableExecutionCount();

        // When
        Execution execution = runnerUtils.runOne(
            "ifflowablecount", "org.flow.tests", "if-condition", null,
            (f, e) -> Map.of("param", true), Duration.ofSeconds(120)
        );

        // Then
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(flowableExecutionCount() - before).isEqualTo(2d);
    }

    private double flowableExecutionCount() {
        return metricRegistry.counter(
            MetricRegistry.METRIC_EXECUTOR_FLOWABLE_EXECUTION_COUNT,
            MetricRegistry.METRIC_EXECUTOR_FLOWABLE_EXECUTION_COUNT_DESCRIPTION,
            MetricRegistry.TAG_TASK_TYPE, If.class.getName()
        ).count();
    }
}