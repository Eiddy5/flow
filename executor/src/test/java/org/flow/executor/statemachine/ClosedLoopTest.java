package org.flow.executor.statemachine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import org.flow.core.models.executions.Execution;
import org.flow.core.executor.command.SessionCommand;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.flows.State;
import org.flow.core.runners.ExecutionEvent;
import org.flow.core.runners.ExecutionEventType;
import org.flow.core.serializers.JacksonMapper;
import org.flow.executor.testkit.Executions;
import org.flow.executor.testkit.ExecutorTestHarness;
import org.flow.executor.testkit.Flows;
import org.flow.executor.testkit.ScriptedWorker;
import org.flow.executor.testkit.Trace;
import org.flow.plugin.core.flow.Pause;
import org.paas.session.Session;
import org.paas.session.User;

import static org.flow.executor.testkit.HarnessAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

/**
 * The production {@code DefaultExecutor}, running: messages are delivered to its own subscribers,
 */
class ClosedLoopTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final ExecutorTestHarness harness = ExecutorTestHarness.create();

    @Test
    void oneDeliveryIsOneProductionStep() {
        // Given
        FlowWithSource flow = Flows.of(Flows.log("a"));
        harness.registerFlow(flow);
        Execution created = Executions.created(flow);

        // When: the execution is delivered on the execution queue
        List<Trace.Emission> emitted = harness.step(created);

        assertThat(harness.stateOf(created)).as("the handler dispatched the first task inside its lock, then the executor announced the update").isEqualTo(State.Type.RUNNING);
        assertThat(emitted).extracting(Trace.Emission::queue).containsExactly("workerJobEvent", "executionEvent", "followExecutionEvent");
        assertThat(emitted.get(1).as(ExecutionEvent.class).eventType()).isEqualTo(ExecutionEventType.UPDATED);
    }

    @Test
    void sequentialTasksRunToSuccessThroughTheLoop() {
        // Given
        FlowWithSource flow = Flows.of(Flows.log("a"), Flows.log("b"));
        harness.registerFlow(flow);
        Execution created = Executions.created(flow);

        // When
        Trace trace = harness.run(created, ScriptedWorker.succeeding(T0));

        assertThat(trace.emitted("workerJobEvent")).as("both tasks went to a worker").hasSize(2);
        assertThat(harness).hasExecutionInState(created, State.Type.SUCCESS);

        List<Trace.Step> terminal = trace.steps().stream()
            .filter(step -> step.emitted("executionEvent").anyMatch(e -> e.as(ExecutionEvent.class).eventType() == ExecutionEventType.TERMINATED))
            .toList();
        assertThat(terminal).as("TERMINATED is published exactly once").hasSize(1);
        int terminalAt = trace.steps().indexOf(terminal.getFirst());
        assertThat(terminal.getFirst().queue())
            .as("two-cycle handoff: TERMINATED is announced by an execution-event delivery, not by the worker-result delivery")
            .isEqualTo("executionEvent");
        assertThat(trace.steps().get(terminalAt - 1).queue())
            .as("the delivery just before it is the worker result that completed the last task")
            .isEqualTo("workerTaskResult");
        assertThat(trace.steps().get(terminalAt - 1).emitted("executionEvent").map(e -> e.as(ExecutionEvent.class).eventType()))
            .as("the worker-result delivery merges the result and only announces UPDATED")
            .containsExactly(ExecutionEventType.UPDATED);
        assertThat(terminal.getFirst().emitted())
            .extracting(Trace.Emission::queue)
            .as("the terminating delivery says nothing else but the terminal events")
            .containsExactly("executionEvent", "followExecutionEvent", "executionTerminated");
        assertThat(trace.emitted("execution")).as("nothing to re-enqueue").isEmpty();
    }

    @Test
    void scheduledExecutionWaitsForTheDelayLoopAndTheClock() {
        // Given: an execution scheduled one hour from now
        FlowWithSource flow = Flows.of(Flows.log("a"));
        harness.registerFlow(flow);
        Execution scheduled = Executions.created(flow).toBuilder().scheduleDate(T0.plus(Duration.ofHours(1))).build();

        // When: it arrives at T0
        harness.clock().set(T0);
        List<Trace.Emission> onArrival = harness.step(scheduled);

        assertThat(onArrival).as("nothing goes to a worker; a delay is parked").extracting(Trace.Emission::queue).doesNotContain("workerJobEvent");
        assertThat(harness.executionDelayStateStore().pending()).hasSize(1);

        // When: the delay loop ticks before the date — nothing happens; after the date — the task is dispatched
        assertThat(harness.tickExecutionDelays(T0.plus(Duration.ofMinutes(30)))).isEmpty();
        List<Trace.Emission> onResume = harness.tickExecutionDelays(T0.plus(Duration.ofHours(2)));
        assertThat(onResume).extracting(Trace.Emission::queue).contains("executionEvent");

        // and the loop finishes the run to SUCCESS through the real subscriptions
        harness.run(List.of(), ScriptedWorker.succeeding(T0.plus(Duration.ofHours(2))));
        assertThat(harness).hasExecutionInState(scheduled, State.Type.SUCCESS);
        assertThat(harness.executionDelayStateStore().pending()).isEmpty();
    }

    /**
     * 验证其他租户无法提交恢复，且合法恢复经 Executor 消费后将提交时的操作者写入 Pause 输出。
     * @throws Exception 当合法恢复无法提交或读取已保存的输出时使测试失败
     */
    @Test
    void resumesWithTheSubmittedActorWithoutRestoringSession() throws Exception {
        var task = Pause.builder().id("review").type(Pause.class.getName()).build();
        var flow = Flows.of(task, Flows.log("after-review"));
        harness.registerFlow(flow);
        var execution = Executions.created(flow);
        var worker = ScriptedWorker.succeeding(T0);
        harness.run(execution, worker);
        assertThat(harness).hasExecutionInState(execution, State.Type.PAUSED);
        var pausedRun = harness.executionStateStore().findByIdWithoutAcl(execution.getId()).getTaskRunList().getFirst();
        var inputs = Map.<String, Object>of("decision", "approved");

        var user = new User();
        user.setId("reviewer");
        user.setCompanyId("another-tenant");
        var session = new Session<User>();
        session.setUser(user);
        assertThatThrownBy(() -> harness.executionService().resume(session, execution.getId(), inputs))
            .isInstanceOf(NoSuchElementException.class);
        assertThat(harness.executionCommandQueue().emitted()).isEmpty();

        user.setCompanyId(flow.getTenantId());
        var command = harness.executionService().resume(session, execution.getId(), inputs);
        assertThat(harness.executionCommandQueue().emitted()).containsExactly(new SessionCommand(command, "reviewer"));
        user.setId("later-request-user");

        harness.run(List.of(), worker);
        assertThat(harness).hasExecutionInState(execution, State.Type.SUCCESS);
        var stored = harness.taskOutputRepository().findById(flow.getTenantId(), pausedRun.getId()).orElseThrow();
        var outputs = JacksonMapper.ofIonBinary().readValue(stored.value(), JacksonMapper.MAP_TYPE_REFERENCE);
        assertThat(outputs).containsEntry("onResume", inputs);
        assertThat(outputs.get("resumed")).asInstanceOf(MAP).containsEntry("by", "reviewer");
        assertThat(harness.taskOutputRepository().findById("another-tenant", pausedRun.getId())).isEmpty();
    }
}
