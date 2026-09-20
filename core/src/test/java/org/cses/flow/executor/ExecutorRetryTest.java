package org.cses.flow.executor;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.plugins.TaskPluginTestSupport;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskResult;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Executor 重复和迟到结果的协议测试；不声称提供真实网络重投验收。 */
class ExecutorRetryTest {
    /** 首次重复失败、第二次运行中的旧失败、完成后的旧结果都不改变当前尝试。 */
    @Test
    void ignoresDuplicateAndLateResultsAcrossAttempts() {
        ExecutorContext context = context(null);
        Execution execution = context.execution();
        ExecutorService executor = new ExecutorService();
        executor.process(context);
        WorkerTask first = executor.dispatch(context, context.takeWorkerTasks().getFirst()).orElseThrow();
        assertEquals(1, first.attemptNumber());
        WorkerTaskResult failed = WorkerTaskResult.failed(first, "first failed");
        executor.applyResult(context, failed);
        var run = execution.requireTaskRun(first.taskRunId());
        Long plan = run.nextRetryAt();
        executor.applyResult(context, failed);
        assertEquals(1, run.attempts().size());
        assertEquals(plan, run.nextRetryAt());
        assertEquals(State.Type.RETRY_WAIT, run.state().current());
        executor.process(context);
        assertTrue(context.workerTasks().isEmpty());
        assertTrue(executor.acceptRetry(context, retryEvent(context), TimeUtil.now()));
        executor.process(context);
        WorkerTask second = executor.dispatch(context, context.takeWorkerTasks().getFirst()).orElseThrow();
        assertEquals(2, second.attemptNumber());
        assertEquals(first.taskRunId(), second.taskRunId());
        assertEquals(first.variables().get("inputs"), second.variables().get("inputs"));
        executor.applyResult(context, failed);
        assertEquals(State.Type.RUNNING, run.state().current());
        assertEquals(2, run.attempts().size());
        assertNull(run.attempts().getLast().completedAt());
        executor.applyResult(context, WorkerTaskResult.success(second, Map.of()));
        executor.applyResult(context, failed);
        executor.applyResult(context, WorkerTaskResult.success(second, Map.of()));
        assertEquals(State.Type.SUCCESS, run.state().current());
        assertEquals(2, run.attempts().size());
        assertEquals("first failed", run.attempts().getFirst().error());
        executor.process(context);
        assertEquals(State.Type.SUCCESS, execution.state().current());
        assertTrue(context.workerTasks().isEmpty());
    }
    /** 已生成待调用计划后才耗尽预算时，实际领取不启动 Worker 或增加尝试次数。 */
    @Test
    void expiredBudgetAtDispatchDoesNotStartAnotherAttempt() {
        ExecutorContext context = context(1000L);
        ExecutorService executor = new ExecutorService();
        executor.process(context);
        WorkerTask first = executor.dispatch(context, context.takeWorkerTasks().getFirst()).orElseThrow();
        executor.applyResult(context, WorkerTaskResult.failed(first, "first failed"));
        assertTrue(executor.acceptRetry(context, retryEvent(context), TimeUtil.now()));
        executor.process(context);
        WorkerTask planned = context.takeWorkerTasks().getFirst();
        long expires = context.execution().requireTaskRun(first.taskRunId()).attempts().getFirst().startedAt() + 1001;
        while (TimeUtil.now() < expires) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        assertTrue(executor.dispatch(context, planned).isEmpty());
        var run = context.execution().requireTaskRun(first.taskRunId());
        assertEquals(1, run.attempts().size());
        assertEquals(State.Type.FAILED, context.execution().state().current());
        assertEquals(State.Type.FAILED, run.state().current());
        assertNull(run.nextRetryAt());
    }

    /** 普通推进不按时间唤醒；提前事件拒绝，错身份或错编号不改变原计划。 */
    @Test
    void onlyMatchingDueRetryEventCanWakeTheTask() {
        ExecutorContext context = context(null);
        ExecutorService executor = new ExecutorService();
        executor.process(context);
        WorkerTask first = executor.dispatch(context, context.takeWorkerTasks().getFirst()).orElseThrow();
        executor.applyResult(context, WorkerTaskResult.failed(first, "first failed"));
        TaskRetryEvent event = retryEvent(context);
        executor.process(context);
        assertTrue(context.workerTasks().isEmpty());
        assertThrows(RuntimeException.class, () -> executor.acceptRetry(context, event, event.deliverAt() - 1));
        assertFalse(executor.acceptRetry(context, TaskRetryEvent.from(event.companyId(), event.executionId(),
            event.taskRunId(), 3, event.deliverAt()), event.deliverAt()));
        assertFalse(executor.acceptRetry(context, TaskRetryEvent.from("other-company", event.executionId(),
            event.taskRunId(), 2, event.deliverAt()), event.deliverAt()));
        var run = context.execution().requireTaskRun(first.taskRunId());
        assertEquals(State.Type.RETRY_WAIT, run.state().current());
        assertEquals(1, run.attemptNumber());
        assertEquals(event.deliverAt(), run.nextRetryAt());
        assertTrue(executor.acceptRetry(context, event, event.deliverAt()));
        assertTrue(executor.acceptRetry(context, event, event.deliverAt()));
        assertEquals(State.Type.CREATED, run.state().current());
        assertEquals(1, run.attemptNumber());
        executor.process(context);
        assertEquals(1, context.workerTasks().size());
        executor.dispatch(context, context.takeWorkerTasks().getFirst()).orElseThrow();
        assertFalse(executor.acceptRetry(context, event, event.deliverAt()));
        assertEquals(2, run.attemptNumber());
    }

    /**
     * @param context 包含唯一待重试任务的上下文
     * @return 持久化计划对应的下一次调用事件
     */
    private TaskRetryEvent retryEvent(ExecutorContext context) {
        var run = context.execution().taskRuns().getFirst();
        return TaskRetryEvent.from(context.execution().companyId(), context.execution().id(), run.id(),
            run.attemptNumber() + 1, run.nextRetryAt());
    }

    /**
     * @param budget 可空的毫秒重试预算
     * @return 独立技术样本运行上下文
     */
    private ExecutorContext context(Long budget) {
        Map<String, Object> retry = new java.util.LinkedHashMap<>(Map.of("type", "constant", "maxAttempts", 2, "intervalMillis", 0));
        if (budget != null) retry.put("maxDurationMillis", budget);
        Flow flow = TaskPluginTestSupport.builtInContext().deploy("retry-technical", "retry-technical", Map.of(
            "key", "retry-technical", "tasks", List.of(Map.of("key", "business", "type", "org.cses.flow.extensions.log.Log",
                "message", "ready", "retry", retry))),
            null, ActorRef.create("retry-user", "Retry User"), TimeUtil.now());
        User user = new User();
        user.setId("retry-user");
        user.setCompanyId(flow.companyId());
        Session<User> session = new Session<>();
        session.setUser(user);
        Execution execution = Execution.create(null, session, flow.key(), flow.version(), Map.of());
        return new ExecutorContext(flow, execution);
    }

}
