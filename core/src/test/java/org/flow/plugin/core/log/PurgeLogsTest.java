package org.flow.plugin.core.log;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.event.Level;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.repositories.LogDataStoreInterface;
import org.flow.core.runners.TestRunnerUtils;
import org.flow.core.services.TaskOutputService;
import org.flow.core.utils.IdUtils;

import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@FlowTest(startRunner = true, startSystemWorker = true)
class PurgeLogsTest {

    @Inject
    private LogDataStoreInterface logRepository;

    @Inject
    protected TestRunnerUtils runnerUtils;

    @Inject
    private TaskOutputService taskOutputService;

    @Test
    @LoadFlows("flows/valids/purge_logs_no_arguments.yaml")
    void run_with_no_arguments() throws Exception {
        // create an execution to delete
        var logEntry = LogEntry.builder()
            .namespace("namespace")
            .flowId("flowId")
            .tenantId(MAIN_TENANT)
            .timestamp(Instant.now())
            .level(Level.INFO)
            .message("Hello World")
            .build();
        logRepository.save(logEntry);

        Execution execution = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "purge_logs_no_arguments");

        assertTrue(execution.getState().isSuccess());
        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat((int) taskOutputService.getOutputs(execution.getTaskRunList().getFirst()).get("count")).isPositive();
    }

    @Test
    @LoadFlows("flows/valids/purge_logs_execution_only.yaml")
    void run_purge_execution_logs_only() throws Exception {
        // create an execution log (with executionId)
        logRepository.save(
            LogEntry.builder()
                .namespace("namespace")
                .flowId("flowId")
                .executionId("exec-123")
                .tenantId(MAIN_TENANT)
                .timestamp(Instant.now())
                .level(Level.INFO)
                .message("Execution log")
                .build()
        );

        // create a non-execution log (without executionId)
        logRepository.save(
            LogEntry.builder()
                .namespace("namespace")
                .flowId("flowId")
                .tenantId(MAIN_TENANT)
                .timestamp(Instant.now())
                .level(Level.INFO)
                .message("Non-execution log")
                .build()
        );

        Execution execution = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "purge_logs_execution_only");

        assertTrue(execution.getState().isSuccess());
        var outputs = taskOutputService.getOutputs(execution.getTaskRunList().getFirst());
        assertThat((int) outputs.get("executionLogsCount")).isPositive();
        assertThat((int) outputs.get("nonExecutionLogsCount")).isZero();
    }

    @Test
    @LoadFlows("flows/valids/purge_logs_trigger_only.yaml")
    void run_purge_non_execution_logs_only() throws Exception {
        // create an execution log (with executionId)
        logRepository.save(
            LogEntry.builder()
                .namespace("namespace")
                .flowId("flowId")
                .executionId("exec-456")
                .tenantId(MAIN_TENANT)
                .timestamp(Instant.now())
                .level(Level.INFO)
                .message("Execution log")
                .build()
        );

        // create a non-execution log (without executionId)
        logRepository.save(
            LogEntry.builder()
                .namespace("namespace")
                .flowId("flowId")
                .tenantId(MAIN_TENANT)
                .timestamp(Instant.now())
                .level(Level.INFO)
                .message("Non-execution log")
                .build()
        );

        Execution execution = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "purge_logs_trigger_only");

        assertTrue(execution.getState().isSuccess());
        var outputs = taskOutputService.getOutputs(execution.getTaskRunList().getFirst());
        assertThat((int) outputs.get("executionLogsCount")).isZero();
        assertThat((int) outputs.get("nonExecutionLogsCount")).isPositive();
    }

    @Test
    @LoadFlows("flows/valids/purge_logs_no_arguments.yaml")
    void run_with_multiple_rows() throws Exception {
        String executionId = IdUtils.create();
        for (int i = 0; i < 5; i++) {
            logRepository.save(
                LogEntry.builder()
                    .namespace("namespace")
                    .flowId("flowId")
                    .executionId(executionId)
                    .tenantId(MAIN_TENANT)
                    .timestamp(Instant.now())
                    .level(Level.INFO)
                    .message("Log entry " + i)
                    .build()
            );
        }

        Execution execution = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "purge_logs_no_arguments");

        assertTrue(execution.getState().isSuccess());
        assertThat((int) taskOutputService.getOutputs(execution.getTaskRunList().getFirst()).get("count")).isGreaterThanOrEqualTo(5);
        assertThat(logRepository.findByExecutionId(MAIN_TENANT, executionId, Level.TRACE)).isEmpty();
    }

    @org.junit.jupiter.api.parallel.Execution(ExecutionMode.SAME_THREAD)
    @ParameterizedTest
    @MethodSource("buildArguments")
    @LoadFlows("flows/valids/purge_logs_full_arguments.yaml")
    void run_with_full_arguments(LogEntry logEntry, int resultCount, String failingReason) throws Exception {
        logRepository.save(logEntry);

        Execution execution = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "purge_logs_full_arguments");

        assertTrue(execution.getState().isSuccess());
        assertThat(execution.getTaskRunList().size()).isEqualTo(1);
        assertThat(taskOutputService.getOutputs(execution.getTaskRunList().getFirst()).get("count")).as(failingReason).isEqualTo(resultCount);
    }

    static Stream<Arguments> buildArguments() {
        return Stream.of(
            Arguments.of(
                LogEntry.builder()
                    .namespace("purge.namespace")
                    .flowId("purgeFlowId")
                    .tenantId(MAIN_TENANT)
                    .timestamp(Instant.now().plus(5, ChronoUnit.HOURS))
                    .level(Level.INFO)
                    .message("Hello World")
                    .build(),
                0, "The log is too recent to be found"
            ),
            Arguments.of(
                LogEntry.builder()
                    .namespace("purge.namespace")
                    .flowId("purgeFlowId")
                    .tenantId(MAIN_TENANT)
                    .timestamp(Instant.now().minus(5, ChronoUnit.HOURS))
                    .level(Level.INFO)
                    .message("Hello World")
                    .build(),
                0, "The log is too old to be found"
            ),
            Arguments.of(
                LogEntry.builder()
                    .namespace("incorrect.namespace")
                    .flowId("purgeFlowId")
                    .tenantId(MAIN_TENANT)
                    .timestamp(Instant.now().minusSeconds(10))
                    .level(Level.INFO)
                    .message("Hello World")
                    .build(),
                0, "The log has an incorrect namespace"
            ),
            Arguments.of(
                LogEntry.builder()
                    .namespace("purge.namespace")
                    .flowId("wrongFlowId")
                    .tenantId(MAIN_TENANT)
                    .timestamp(Instant.now().minusSeconds(10))
                    .level(Level.INFO)
                    .message("Hello World")
                    .build(),
                0, "The log has an incorrect flow id"
            ),
            Arguments.of(
                LogEntry.builder()
                    .namespace("purge.namespace")
                    .flowId("purgeFlowId")
                    .tenantId(MAIN_TENANT)
                    .timestamp(Instant.now().minusSeconds(10))
                    .level(Level.WARN)
                    .message("Hello World")
                    .build(),
                0, "The log has an incorrect LogLevel"
            ),
            Arguments.of(
                LogEntry.builder()
                    .namespace("purge.namespace")
                    .flowId("purgeFlowId")
                    .tenantId(MAIN_TENANT)
                    .timestamp(Instant.now().minusSeconds(10))
                    .level(Level.INFO)
                    .message("Hello World")
                    .build(),
                1, "The log should be deleted"
            )
        );
    }
}