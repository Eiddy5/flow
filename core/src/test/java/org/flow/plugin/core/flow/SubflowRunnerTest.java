package org.flow.plugin.core.flow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import org.flow.core.executor.command.Create;
import org.flow.core.executor.command.ExecutionCommand;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.models.Label;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionId;
import org.flow.core.models.executions.ExecutionKind;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.models.flows.State;
import org.flow.core.queues.*;
import org.flow.core.repositories.ExecutionRepositoryInterface;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.runners.ExecutionEventType;
import org.flow.core.runners.FlowMetaStoreInterface;
import org.flow.core.runners.FollowExecutionEvent;
import org.flow.core.runners.TestRunnerUtils;
import org.flow.core.services.TaskOutputService;
import org.flow.core.utils.IdUtils;

import io.micronaut.context.annotation.Property;
import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

@FlowTest(startRunner = true)
@Property(name = "flow.execution.depth.max-depth", value = "3")
class SubflowRunnerTest {

    @Inject
    private TestRunnerUtils runnerUtils;

    @Inject
    private ExecutionRepositoryInterface executionRepository;

    @Inject
    private FlowRepositoryInterface flowRepository;

    @Inject
    private FlowMetaStoreInterface flowMetaStore;

    @Inject
    protected BroadcastQueueInterface<FlowInterface> flowQueue;

    @Inject
    protected BroadcastQueueInterface<FollowExecutionEvent> executionEventQueue;

    @Inject
    protected DispatchQueueInterface<ExecutionCommand> executionCommandQueue;

    @Inject
    private TaskOutputService taskOutputService;

    @Inject
    private DispatchQueueInterface<LogEntry> logQueue;

    @Test
    @LoadFlows({ "flows/valids/subflow-inherited-labels-child.yaml", "flows/valids/subflow-inherited-labels-parent.yaml" })
    void inheritedLabelsAreOverridden() throws QueueException, TimeoutException, org.flow.core.exceptions.InternalException {
        Execution parentExecution = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "subflow-inherited-labels-parent");

        assertThat(parentExecution.getLabels()).containsExactlyInAnyOrder(
            new Label(Label.CORRELATION_ID, parentExecution.getId()),
            new Label("parentFlowLabel1", "value1"),
            new Label("parentFlowLabel2", "value2")
        );

        String childExecutionId = (String) taskOutputService.getOutputs(parentExecution.findTaskRunsByTaskId("launch").getFirst()).get("executionId");

        assertThat(childExecutionId).isNotBlank();

        Execution childExecution = executionRepository.findById(MAIN_TENANT, childExecutionId).orElseThrow();

        assertThat(childExecution.getLabels()).containsExactlyInAnyOrder(
            new Label(Label.CORRELATION_ID, parentExecution.getId()), // parent's correlation ID
            new Label("childFlowLabel1", "value1"), // defined by the subtask flow
            new Label("childFlowLabel2", "value2"), // defined by the subtask flow
            new Label("launchTaskLabel", "launchFoo"), // added by Subtask
            new Label("parentFlowLabel1", "launchBar"), // overridden by Subtask
            new Label("parentFlowLabel2", "value2") // inherited from the parent flow
        );
    }

    @Test
    @LoadFlows({ "flows/valids/subflow-parent-no-wait.yaml", "flows/valids/subflow-child-with-output.yaml" })
    void subflowOutputWithoutWait() throws QueueException, TimeoutException, InterruptedException, org.flow.core.exceptions.InternalException {
        AtomicReference<Execution> childExecution = new AtomicReference<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        QueueSubscriber<FollowExecutionEvent> closing = executionEventQueue.subscriber().subscribe(either ->
        {
            if (either.isLeft() && either.getLeft().flowId().equals("subflow-child-with-output") && either.getLeft().eventType() == ExecutionEventType.TERMINATED) {
                childExecution.set(executionRepository.findById(either.getLeft().tenantId(), either.getLeft().executionId()).orElseThrow());
                countDownLatch.countDown();
            }
        });

        Execution parentExecution = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "subflow-parent-no-wait");
        String childExecutionId = (String) taskOutputService.getOutputs(parentExecution.findTaskRunsByTaskId("subflow").getFirst()).get("executionId");
        assertThat(childExecutionId).isNotBlank();
        assertThat(parentExecution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(parentExecution.getTaskRunList()).hasSize(1);

        assertTrue(countDownLatch.await(10, TimeUnit.SECONDS));
        assertThat(childExecution.get().getId()).isEqualTo(childExecutionId);
        assertThat(childExecution.get().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(childExecution.get().getTaskRunList()).hasSize(1);
        closing.close();
    }

    @Test
    @LoadFlows({ "flows/valids/subflow-parent-retry.yaml", "flows/valids/subflow-to-retry.yaml" })
    void subflowOutputWithWait() throws QueueException, TimeoutException, InterruptedException {
        List<Execution> childExecution = new ArrayList<>();
        CountDownLatch countDownLatch = new CountDownLatch(4);
        QueueSubscriber<FollowExecutionEvent> closing = executionEventQueue.subscriber().subscribe(either ->
        {
            if (either.isLeft() && either.getLeft().flowId().equals("subflow-to-retry") && either.getLeft().eventType() == ExecutionEventType.TERMINATED) {
                var execution = executionRepository.findById(either.getLeft().tenantId(), either.getLeft().executionId()).orElseThrow();
                childExecution.add(execution);
                countDownLatch.countDown();
            }
        });

        Execution parentExecution = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "subflow-parent-retry");
        assertThat(parentExecution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(parentExecution.getTaskRunList()).hasSize(5);

        assertTrue(countDownLatch.await(10, TimeUnit.SECONDS));
        // we should have 4 executions, two in SUCCESS and two in FAILED
        assertThat(childExecution).hasSize(4);
        assertThat(childExecution.stream().filter(e -> e.getState().getCurrent() == State.Type.SUCCESS).count()).isEqualTo(2);
        assertThat(childExecution.stream().filter(e -> e.getState().getCurrent() == State.Type.FAILED).count()).isEqualTo(2);
        closing.close();
    }

    @Test
    @LoadFlows({ "flows/valids/subflow-nullable-input-parent.yaml", "flows/valids/subflow-nullable-input-child.yaml" })
    void shouldPassNullableInputFromParentToSubflow() throws QueueException, TimeoutException, org.flow.core.exceptions.InternalException {
        // Given — the parent flow has an optional INT input provided as an empty string, reproducing
        // the case where a user leaves an optional input blank in the UI (or the API sends "").
        // The Subflow task forwards it via "{{ inputs.integerValue }}", which Pebble renders to "".
        // Without the fix this fails with "Invalid input for integerValue, For input string: \"\"";
        // with the fix both parent and child should reach SUCCESS.

        // When
        Execution parentExecution = runnerUtils.runOne(
            MAIN_TENANT, "org.flow.tests", "subflow-nullable-input-parent",
            null, (f, e) -> Map.of("integerValue", "")
        );

        // Then — parent reaches SUCCESS
        assertThat(parentExecution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        String childExecutionId = (String) taskOutputService.getOutputs(
            parentExecution.findTaskRunsByTaskId("subflow").getFirst()
        ).get("executionId");
        assertThat(childExecutionId).isNotBlank();

        Execution childExecution = executionRepository.findById(MAIN_TENANT, childExecutionId).orElseThrow();

        // Then — child also reaches SUCCESS with null input
        assertThat(childExecution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
    }

    @Test
    @LoadFlows({ "flows/valids/subflow-parent.yaml", "flows/valids/subflow-child.yaml", "flows/valids/subflow-grand-child.yaml" })
    void subflowShouldTransmitKind() throws QueueException, org.flow.core.exceptions.InternalException {
        Flow parent = flowRepository.findById(MAIN_TENANT, "org.flow.tests", "subflow-parent").orElseThrow();
        String executionId = IdUtils.create();
        executionCommandQueue.emit(
            Create.of(new ExecutionId(parent.getTenantId(), parent.getNamespace(), parent.getId(), executionId, parent.getRevision()))
                .withKind(ExecutionKind.TEST)
        );

        Execution parentExecution = runnerUtils.awaitFlowExecution(
            e -> executionId.equals(e.getId()) && e.getState().isTerminated(),
            MAIN_TENANT, "org.flow.tests", "subflow-parent"
        );
        assertThat(parentExecution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(parentExecution.getTaskRunList()).hasSize(1);
        String childExecutionId = (String) taskOutputService.getOutputs(parentExecution.findTaskRunsByTaskId("subflow").getFirst()).get("executionId");
        assertThat(childExecutionId).isNotBlank();

        Optional<Execution> childExecution = executionRepository.findById(MAIN_TENANT, childExecutionId);
        assertTrue(childExecution.isPresent());
        assertThat(childExecution.get().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(childExecution.get().getTaskRunList()).hasSize(1);
        String grandChildExecutionId = (String) taskOutputService.getOutputs(parentExecution.findTaskRunsByTaskId("subflow").getFirst()).get("executionId");
        assertThat(grandChildExecutionId).isNotBlank();

        Optional<Execution> grandChildExecution = executionRepository.findById(MAIN_TENANT, grandChildExecutionId);
        assertTrue(grandChildExecution.isPresent());
        assertThat(grandChildExecution.get().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(grandChildExecution.get().getTaskRunList()).hasSize(1);
    }

    @Test
    void shouldFailRatherThanRecurseForeverWhenTwoFlowsSubflowEachOther() throws QueueException, TimeoutException {
        // Given two flows that Subflow-call each other. FlowService.create() now rejects this shape (see FlowServiceTest),
        // so the pair is seeded directly through the repository and flow queue, bypassing that save-time check.
        String flowAId = IdUtils.create();
        String flowBId = IdUtils.create();
        Flow flowA = Flow.builder()
            .id(flowAId)
            .tenantId(MAIN_TENANT)
            .namespace("org.flow.tests")
            .tasks(List.of(Subflow.builder().id("call").type(Subflow.class.getName()).namespace("org.flow.tests").flowId(flowBId).build()))
            .build();
        Flow flowB = Flow.builder()
            .id(flowBId)
            .tenantId(MAIN_TENANT)
            .namespace("org.flow.tests")
            .tasks(List.of(Subflow.builder().id("call").type(Subflow.class.getName()).namespace("org.flow.tests").flowId(flowAId).build()))
            .build();
        flowRepository.create(GenericFlow.of(flowA));
        flowRepository.create(GenericFlow.of(flowB));
        flowQueue.emit(flowA);
        flowQueue.emit(flowB);
        await().atMost(Duration.ofSeconds(10)).until(
            () -> flowMetaStore.allLastVersion().stream().anyMatch(f -> f.getId().equals(flowAId))
                && flowMetaStore.allLastVersion().stream().anyMatch(f -> f.getId().equals(flowBId))
        );

        // When
        Execution execution = runnerUtils.runOne(flowA, null);

        // Then the chain is stopped rather than left to recurse forever
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }

    @Test
    @LoadFlows({ "flows/valids/subflow-secret-output-parent.yaml", "flows/valids/subflow-secret-output-child.yaml" })
    void shouldMaskSecretWhenParentLogsSubflowSecretOutput() throws QueueException, TimeoutException, InterruptedException, org.flow.core.exceptions.InternalException {
        // Given — a listener on the log queue for the parent's `log` task
        AtomicReference<LogEntry> logEntry = new AtomicReference<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        logQueue.addListener(left ->
        {
            if (left.getFlowId().equals("subflow-secret-output-parent") && left.getTaskId().equals("log")) {
                logEntry.set(left);
                countDownLatch.countDown();
            }
        });

        // When — the parent flow logs a SECRET-typed output produced by its subflow
        Execution execution = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "subflow-secret-output-parent");

        // Then — the parent's log task masks the decrypted secret instead of printing it in plaintext
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertTrue(countDownLatch.await(10, TimeUnit.SECONDS));
        assertThat(logEntry.get()).isNotNull();
        assertThat(logEntry.get().getMessage()).isEqualTo("secret is ******");
    }
}
