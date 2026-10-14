package org.flow.worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionKilledExecution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.State;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.ResolvedTask;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.KeyedDispatchQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.runners.Worker;
import org.flow.core.runners.WorkerJobEvent;
import org.flow.core.runners.WorkerTask;
import org.flow.core.runners.WorkerTaskData;
import org.flow.core.runners.WorkerTaskResult;
import org.flow.core.utils.IdUtils;
import org.flow.core.utils.TestsUtils;
import org.flow.plugin.core.flow.Pause;
import org.flow.plugin.core.flow.Sleep;
import org.flow.plugin.core.flow.WorkingDirectory;
import org.flow.worker.services.ExecutionKilledManager;

import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

@FlowTest(rebuildContext = true)
class WorkerTest {

    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private KeyedDispatchQueueInterface<WorkerJobEvent> workerJobEventQueue;

    @Inject
    private DispatchQueueInterface<WorkerTaskResult> workerTaskResultQueue;

    @Inject
    private ExecutionKilledManager executionKilledManager;

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldSucceedWhenTaskIsExecuted() throws QueueException {
        // Given
        List<WorkerTaskResult> results = new ArrayList<>();
        workerTaskResultQueue.addListener(results::add);

        // When
        try (Worker worker = applicationContext.createBean(Worker.class)) {
            worker.start(1);
            workerJobEventQueue.emit(null, WorkerJobEvent.of(workerTask(Duration.ofSeconds(1)), null));

            await()
                .atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> !results.isEmpty() && results.getLast().getTaskRun().getState().isTerminated());
        }

        // Then
        assertThat(results.getLast().getTaskRun().getState().getHistories()).hasSize(3);
    }

    @Test
    void shouldFailWhenWorkerReceivesFlowableTask() throws QueueException {
        // Given
        Pause pause = Pause.builder()
            .type(Pause.class.getName())
            .pauseDuration(Property.ofValue(Duration.ofSeconds(1)))
            .id("unit-test")
            .build();

        WorkingDirectory flowableTask = WorkingDirectory.builder()
            .type(WorkingDirectory.class.getName())
            .id("worker-unit-test")
            .tasks(List.of(pause))
            .build();

        Flow flow = Flow.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unit-test")
            .tasks(List.of(flowableTask))
            .build();

        Execution execution = TestsUtils.mockExecution(flow, Map.of());
        ResolvedTask resolvedTask = ResolvedTask.of(pause);
        WorkerTask workerTask = WorkerTask.builder()
            .data(WorkerTaskData.from(runContextFactory.of(Map.of("key", "value"))))
            .task(flowableTask)
            .taskRun(TaskRun.of(execution, resolvedTask))
            .build();

        List<WorkerTaskResult> results = new ArrayList<>();
        workerTaskResultQueue.addListener(results::add);

        // When
        try (Worker worker = applicationContext.createBean(Worker.class)) {
            worker.start(1);
            workerJobEventQueue.emit(null, WorkerJobEvent.of(workerTask, null));

            await()
                .atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> !results.isEmpty() && results.getLast().getTaskRun().getState().isFailed());
        }

        // Then
        assertThat(results.getLast().getTaskRun().getState().getHistories()).hasSize(3);
    }

    @Test
    void shouldKillTasksWhenExecutionKillEventReceived() throws QueueException {
        // Given
        List<WorkerTaskResult> results = new CopyOnWriteArrayList<>();
        workerTaskResultQueue.addListener(results::add);

        String executionId = IdUtils.create();

        try (Worker worker = applicationContext.createBean(Worker.class)) {
            worker.start(2);

            await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> worker.getState() == org.flow.core.server.Service.ServiceState.RUNNING);

            // one long task to kill, one short task with a different executionId to keep
            workerJobEventQueue.emit(null, WorkerJobEvent.of(workerTask(Duration.ofSeconds(60), executionId), null));
            workerJobEventQueue.emit(null, WorkerJobEvent.of(workerTask(Duration.ofSeconds(1)), null));

            // Wait until both jobs are running AND the kill target has transitioned to RUNNING
            // state. getRunningJobs() is satisfied before the task-run state is updated, so
            // sending the kill without the second condition races and produces [CREATED, KILLED]
            // instead of [CREATED, RUNNING, KILLED].
            await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(
                    () -> worker.getRunningJobs().size() >= 2
                        && results.stream().anyMatch(
                            r -> r.getTaskRun().getExecutionId().equals(executionId)
                                && r.getTaskRun().getState().getCurrent() == State.Type.RUNNING
                        )
                );

            // When
            ExecutionKilledExecution killedExecution = ExecutionKilledExecution.builder()
                .executionId(executionId)
                .build();
            executionKilledManager.onKillReceived(killedExecution);

            await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> results.stream().filter(r -> r.getTaskRun().getState().isTerminated()).count() == 2);

            // Then
            WorkerTaskResult killed = results.stream()
                .filter(r -> r.getTaskRun().getState().getCurrent() == State.Type.KILLED)
                .findFirst()
                .orElseThrow();
            assertThat(killed.getTaskRun().getState().getHistories()).hasSize(3);

            WorkerTaskResult succeeded = results.stream()
                .filter(r -> r.getTaskRun().getState().getCurrent() == State.Type.SUCCESS)
                .findFirst()
                .orElseThrow();
            assertThat(succeeded.getTaskRun().getState().getHistories()).hasSize(3);
        }
    }

    @Test
    void shouldCreateInstanceGivenApplicationContext() {
        assertThatCode(() ->
        {
            try (var worker = applicationContext.getBean(Worker.class)) {
                // do nothing
            }
        }).doesNotThrowAnyException();
    }

    private WorkerTask workerTask(Duration duration) {
        return workerTask(duration, IdUtils.create());
    }

    private WorkerTask workerTask(Duration duration, String executionId) {
        Sleep bash = Sleep.builder()
            .type(Sleep.class.getName())
            .id("unit-test")
            .duration(Property.ofValue(duration))
            .build();

        Flow flow = Flow.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unit-test")
            .tasks(List.of(bash))
            .build();

        Execution execution = TestsUtils.mockExecution(flow, Map.of());
        execution = execution.toBuilder().id(executionId).build();

        ResolvedTask resolvedTask = ResolvedTask.of(bash);

        return WorkerTask.builder()
            .data(WorkerTaskData.from(runContextFactory.of(Map.of("key", "value"))))
            .task(bash)
            .taskRun(TaskRun.of(execution, resolvedTask))
            .build();
    }
}