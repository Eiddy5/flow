package org.cses.flow.core.services.executions;

import com.sun.net.httpserver.HttpServer;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.cses.flow.core.utils.TimeUtil;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/** UC: docs/uc/flow/UC-18 用户重试失败的流程步骤.md */
class Uc18TaskRetryTest {
    /** 固定间隔的三次实际 HTTP 调用保留身份和失败历史，前后步骤各执行一次。 */
    @Test
    void s1ConstantRetrySucceeds() throws Exception {
        try (Business business = new Business(2); WorkflowUcFixture fixture = open()) {
            Flow flow = fixture.deploy(yaml(business, constant(3, 1000), false));
            String id = fixture.startCreated(flow).id();
            List<Long> plans = observeWaits(fixture, flow, id, business, 2, new long[]{1000, 1000});
            assertSuccess(fixture, flow, id, business, 3, plans, State.Type.SUCCESS);
        }
    }

    /** 指数策略从失败时间计算四次间隔，并在三秒上限停止增长。 */
    @Test
    void s2ExponentialRetryCapsIntervals() throws Exception {
        try (Business business = new Business(4); WorkflowUcFixture fixture = open()) {
            Flow flow = fixture.deploy(yaml(business, "{type: exponential, maxAttempts: 5, intervalMillis: 1000, maxIntervalMillis: 3000, delayFactor: 2}", false));
            String id = fixture.startCreated(flow).id();
            List<Long> plans = observeWaits(fixture, flow, id, business, 4, new long[]{1000, 2000, 3000, 3000});
            assertSuccess(fixture, flow, id, business, 5, plans, State.Type.SUCCESS);
        }
    }

    /** 同一次随机等待的计划稳定，实际调用不早于计划。 */
    @Test
    void s3RandomRetryKeepsOnePersistedPlan() throws Exception {
        try (Business business = new Business(2); WorkflowUcFixture fixture = open()) {
            Flow flow = fixture.deploy(yaml(business, "{type: random, maxAttempts: 3, minIntervalMillis: 1000, maxIntervalMillis: 3000}", false));
            String id = fixture.startCreated(flow).id();
            List<Long> plans = observeWaits(fixture, flow, id, business, 2, null);
            assertSuccess(fixture, flow, id, business, 3, plans, State.Type.SUCCESS);
        }
    }

    /** 三次失败后终止，不生成第四次计划或后置输出。 */
    @Test
    void s4ExhaustedAttemptsFailTheFlow() throws Exception {
        try (Business business = new Business(99); WorkflowUcFixture fixture = open()) {
            Flow flow = fixture.deploy(yaml(business, constant(3, 1000), false));
            String id = fixture.startCreated(flow).id();
            observeWaits(fixture, flow, id, business, 2, new long[]{1000, 1000});
            assertFailure(fixture, flow, id, business, 3);
        }
    }

    /** 超出时间预算的下一次计划被拒绝，即使次数仍有余额。 */
    @Test
    void s5DurationBudgetStopsFurtherAttempts() throws Exception {
        try (Business business = new Business(99, true); WorkflowUcFixture fixture = open()) {
            Flow flow = fixture.deploy(yaml(business, "{type: constant, maxAttempts: 10, intervalMillis: 2000, maxDurationMillis: 1000}", false));
            String id = fixture.startCreated(flow).id();
            Execution running = fixture.awaitExecution(fixture.session(), id, e -> e.taskRunsForTask(taskId(flow, "business")).stream()
                .anyMatch(t -> t.state().is(State.Type.RUNNING)));
            long budgetEnd = run(running, flow, "business").attempts().getFirst().startedAt() + 1100;
            while (TimeUtil.now() < budgetEnd) {
                assertEquals(State.Type.RUNNING, run(query(fixture, id), flow, "business").state().current());
                LockSupport.parkNanos(10_000_000);
            }
            assertEquals(State.Type.RUNNING, run(query(fixture, id), flow, "business").state().current());
            business.release.countDown();
            assertFailure(fixture, flow, id, business, 1);
        }
    }

    /** 零间隔重试成功仍显示警告，并允许后置消费成功输出。 */
    @Test
    void s6SuccessAfterRetryRetainsWarning() throws Exception {
        try (Business business = new Business(1); WorkflowUcFixture fixture = open()) {
            Flow flow = fixture.deploy(yaml(business, "{type: constant, maxAttempts: 2, intervalMillis: 0, warningOnRetry: true}", false));
            assertSuccess(fixture, flow, fixture.startCreated(flow).id(), business, 2, List.of(), State.Type.WARNING);
        }
    }

    /** 真正重建 ApplicationContext 后依原计划继续，不重跑前置步骤。 */
    @Test
    void s7RestartPreservesPendingRetry() throws Exception {
        try (Business business = new Business(1); WorkflowUcFixture fixture = open()) {
            Flow flow = fixture.deploy(yaml(business, constant(2, 10000), false));
            String id = fixture.startCreated(flow).id();
            TaskRun waiting = waitForAttempt(fixture, flow, id, 1);
            long plan = waiting.nextRetryAt();
            String runId = waiting.id();
            fixture.restartServer();
            Execution restored = query(fixture, id);
            assertEquals(runId, run(restored, flow, "business").id());
            assertEquals(plan, run(restored, flow, "business").nextRetryAt());
            assertHistory(run(restored, flow, "business"), 1, false);
            assertSuccess(fixture, flow, id, business, 2, List.of(plan), State.Type.SUCCESS);
        }
    }

    /** 取消后跨过原计划时间，业务调用和取消结果都保持不变。 */
    @Test
    void s8CancelSuppressesDueRetry() throws Exception {
        try (Business business = new Business(99); WorkflowUcFixture fixture = open()) {
            Flow flow = fixture.deploy(yaml(business, constant(3, 10000), false));
            String id = fixture.startCreated(flow).id();
            TaskRun waiting = waitForAttempt(fixture, flow, id, 1);
            long plan = waiting.nextRetryAt();
            assertEquals(State.Type.KILLED, fixture.cancel(id).state().current());
            while (TimeUtil.now() <= plan + 1500) {
                assertEquals(State.Type.KILLED, query(fixture, id).state().current());
                assertEquals(1, business.calls("business").size());
                LockSupport.parkNanos(10_000_000);
            }
            Execution killed = query(fixture, id);
            assertHistory(run(killed, flow, "business"), 1, false);
            assertNull(run(killed, flow, "business").nextRetryAt());
            assertTrue(business.calls("after").isEmpty());
            assertClosed(fixture, killed);
        }
    }

    /** 真实重试闭环；重复结果传输缺少公开注入入口，报告必须保持 S9 NOT_COVERED。 */
    @Test
    void s9RetryHistoryRemainsQueryable() throws Exception {
        try (Business business = new Business(1); WorkflowUcFixture fixture = open()) {
            Flow flow = fixture.deploy(yaml(business, constant(2, 1000), false));
            String id = fixture.startCreated(flow).id();
            List<Long> plans = observeWaits(fixture, flow, id, business, 1, new long[]{1000});
            assertSuccess(fixture, flow, id, business, 2, plans, State.Type.SUCCESS);
        }
    }

    /** 无配置和最多一次都仅执行首次调用；参数分别建立独立流程。
     * @param retry 是否显式配置最多一次
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void s10NoRetryFailsImmediately(boolean retry) throws Exception {
        try (Business business = new Business(99); WorkflowUcFixture fixture = open()) {
            Flow flow = fixture.deploy(yaml(business, retry ? constant(1, 1000) : null, false));
            assertFailure(fixture, flow, fixture.startCreated(flow).id(), business, 1);
        }
    }

    /** 保存非法草稿后发布拒绝，草稿内容不变且能够删除。
     * @param invalid 非法 retry 配置
     */
    @ParameterizedTest
    @ValueSource(strings = {"{type: constant, maxAttempts: 0, intervalMillis: 1000}", "{type: constant, maxAttempts: -1, intervalMillis: 1000}", "{type: constant, maxAttempts: 3, intervalMillis: -1}"})
    void s11InvalidRetryCannotBePublished(String invalid) throws Exception {
        try (Business business = new Business(0); WorkflowUcFixture fixture = open()) {
            String source = yaml(business, invalid, false);
            Flow draft = fixture.flowService().save(fixture.session(), PublishFlowCommand.from(source));
            assertEquals(source, fixture.flowService().draft(fixture.session(), draft.key()).orElseThrow().source());
            RuntimeException failure = assertThrows(RuntimeException.class, () -> fixture.flowService().save(fixture.session(), PublishFlowCommand.from(draft.key(), false)));
            assertFalse(failure.getMessage().isBlank());
            assertEquals(source, fixture.flowService().draft(fixture.session(), draft.key()).orElseThrow().source());
            assertTrue(fixture.flowService().latestFlow(fixture.session(), draft.key()).isEmpty());
            assertTrue(fixture.executionService().executions(fixture.session()).isEmpty());
            assertTrue(business.calls.isEmpty());
            fixture.flowService().delete(fixture.session(), draft.key(), true);
            assertTrue(fixture.flowService().draft(fixture.session(), draft.key()).isEmpty());
        }
    }

    /** 重试等待期间其他并行分支完成，汇总只在双方成功后消费一次。 */
    @Test
    void s12OtherParallelBranchContinues() throws Exception {
        try (Business business = new Business(1); WorkflowUcFixture fixture = open()) {
            Flow flow = fixture.deploy(yaml(business, constant(2, 10000), true));
            String id = fixture.startCreated(flow).id();
            TaskRun waiting = waitForAttempt(fixture, flow, id, 1);
            long plan = waiting.nextRetryAt();
            Execution parallel = fixture.awaitExecution(fixture.session(), id, e -> e.taskRunsForTask(taskId(flow, "side")).stream().anyMatch(t -> t.state().is(State.Type.SUCCESS)));
            assertTrue(TimeUtil.now() < plan);
            assertEquals(State.Type.RETRY_WAIT, run(parallel, flow, "business").state().current());
            assertEquals(1, business.calls("side").size());
            assertTrue(business.calls("after").isEmpty());
            Execution completed = awaitTerminal(fixture, id);
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(2, business.calls("business").size());
            assertEquals(1, business.calls("side").size());
            assertEquals(List.of("ready+ready"), business.calls("after").stream().map(Call::body).toList());
            assertTrue(business.calls("business").get(1).at() >= plan);
            assertHistory(run(completed, flow, "business"), 2, true);
            assertClosed(fixture, completed);
        }
    }

    /**
     * @return 使用真实装配且启用样本插件的新夹具
     */
    private WorkflowUcFixture open() { return WorkflowUcFixture.openWithProperties(Map.of("flow.test.retry-task", true)); }
    /**
     * @param count 总次数
     * @param interval 毫秒间隔
     * @return 内联策略配置
     */
    private String constant(int count, long interval) { return "{type: constant, maxAttempts: " + count + ", intervalMillis: " + interval + "}"; }
    /**
     * @param fixture 当前公开入口
     * @param id 运行编号
     * @return 最新公开快照
     */
    private Execution query(WorkflowUcFixture fixture, String id) { return fixture.executionService().execution(fixture.session(), id).orElseThrow(); }
    /**
     * @param flow 发布定义
     * @param key 步骤业务标识
     * @return 对应定义编号
     */
    private String taskId(Flow flow, String key) { return flow.allTasks().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow().id(); }
    /**
     * @param execution 公开快照
     * @param flow 定义
     * @param key 步骤标识
     * @return 唯一运行记录
     */
    private TaskRun run(Execution execution, Flow flow, String key) {
        List<TaskRun> runs = execution.taskRunsForTask(taskId(flow, key));
        assertEquals(1, runs.size());
        return runs.getFirst();
    }
    /**
     * @param fixture 当前公开入口
     * @param flow 定义
     * @param id 运行编号
     * @param number 失败次数
     * @return 等待重试快照
     */
    private TaskRun waitForAttempt(WorkflowUcFixture fixture, Flow flow, String id, int number) {
        Execution waiting = fixture.awaitExecution(fixture.session(), id, e -> e.taskRunsForTask(taskId(flow, "business")).stream()
            .anyMatch(t -> t.state().is(State.Type.RETRY_WAIT) && t.attempts().size() == number));
        assertEquals(State.Type.RUNNING, waiting.state().current());
        return run(waiting, flow, "business");
    }
    /**
     * @param fixture 公开入口
     * @param flow 定义
     * @param id 运行编号
     * @param business 外部业务
     * @param count 失败次数
     * @param delays 预期间隔，null 表示随机范围
     * @return 查询到的计划时间
     */
    private List<Long> observeWaits(WorkflowUcFixture fixture, Flow flow, String id, Business business, int count, long[] delays) {
        List<Long> plans = new ArrayList<>();
        String runId = null;
        for (int i = 1; i <= count; i++) {
            TaskRun waiting = waitForAttempt(fixture, flow, id, i);
            if (runId == null) runId = waiting.id();
            assertEquals(runId, waiting.id());
            assertHistory(waiting, i, false);
            long plan = waiting.nextRetryAt();
            long delay = plan - waiting.attempts().getLast().completedAt();
            if (delays == null) assertTrue(delay >= 1000 && delay < 3000);
            else assertEquals(delays[i - 1], delay);
            plans.add(plan);
            while (TimeUtil.now() < plan - 40) {
                TaskRun again = run(query(fixture, id), flow, "business");
                if (TimeUtil.now() >= plan) break;
                assertEquals(plan, again.nextRetryAt());
                assertEquals(i, business.calls("business").size());
                assertTrue(business.calls("after").isEmpty());
                LockSupport.parkNanos(10_000_000);
            }
        }
        return plans;
    }
    /**
     * @param fixture 公开入口
     * @param id 运行编号
     * @return 30 秒内查询到的终态，否则断言失败
     */
    private Execution awaitTerminal(WorkflowUcFixture fixture, String id) {
        long deadline = TimeUtil.now() + 30000;
        while (TimeUtil.now() < deadline) {
            Execution current = query(fixture, id);
            if (current.isTerminal()) return current;
            LockSupport.parkNanos(10_000_000);
        }
        fail("Execution did not finish: " + id + ": " + query(fixture, id).state().current());
        return null;
    }
    /**
     * @param run 公开运行
     * @param count 总尝试次数
     * @param success 最后一次是否成功
     */
    private void assertHistory(TaskRun run, int count, boolean success) {
        assertEquals(count, run.attempts().size());
        for (int i = 0; i < count; i++) {
            var attempt = run.attempts().get(i);
            assertEquals(i + 1, attempt.number());
            assertTrue(attempt.startedAt() > 0);
            assertNotNull(attempt.completedAt());
            assertTrue(attempt.completedAt() >= attempt.startedAt());
            if (success && i == count - 1) {
                assertEquals(State.Type.SUCCESS, attempt.state());
            } else {
                assertEquals(State.Type.FAILED, attempt.state());
                assertTrue(attempt.error().contains("business-failure-" + (i + 1)));
            }
        }
    }
    /**
     * @param fixture 公开入口
     * @param flow 定义
     * @param id 运行编号
     * @param business 外部业务
     * @param count 实际次数
     * @param plans 已观察计划
     * @param state 步骤成功或警告状态
     */
    private void assertSuccess(WorkflowUcFixture fixture, Flow flow, String id, Business business, int count, List<Long> plans, State.Type state) {
        Execution completed = awaitTerminal(fixture, id);
        if (state == State.Type.SUCCESS) assertEquals(State.Type.SUCCESS, completed.state().current());
        else assertTrue(completed.isTerminal());
        TaskRun target = run(completed, flow, "business");
        assertEquals(state, target.state().current());
        assertHistory(target, count, true);
        assertEquals("ready", target.outputs().get("value"));
        assertEquals(count, business.calls("business").size());
        for (int i = 0; i < count; i++) {
            long calledAt = business.calls("business").get(i).at();
            assertTrue(calledAt >= target.attempts().get(i).startedAt());
            assertTrue(calledAt <= target.attempts().get(i).completedAt());
        }
        assertEquals(1, business.calls("before").size());
        assertEquals(List.of("ready"), business.calls("after").stream().map(Call::body).toList());
        for (Call call : business.calls("business")) {
            assertEquals(id, call.executionId());
            assertEquals(target.id(), call.taskRunId());
        }
        for (int i = 0; i < plans.size(); i++) assertTrue(business.calls("business").get(i + 1).at() >= plans.get(i));
        assertClosed(fixture, completed);
    }
    /**
     * @param fixture 公开入口
     * @param flow 定义
     * @param id 运行编号
     * @param business 外部业务
     * @param count 失败次数
     */
    private void assertFailure(WorkflowUcFixture fixture, Flow flow, String id, Business business, int count) {
        Execution failed = awaitTerminal(fixture, id);
        assertEquals(State.Type.FAILED, failed.state().current());
        TaskRun target = run(failed, flow, "business");
        assertEquals(State.Type.FAILED, target.state().current());
        assertHistory(target, count, false);
        assertTrue(target.outputs().isEmpty());
        assertNull(target.nextRetryAt());
        assertEquals(count, business.calls("business").size());
        assertTrue(business.calls("after").isEmpty());
        assertClosed(fixture, failed);
    }
    /**
     * @param fixture 公开入口
     * @param execution 已结束运行
     */
    private void assertClosed(WorkflowUcFixture fixture, Execution execution) {
        assertTrue(execution.isTerminal());
        assertTrue(execution.activeTaskRuns().isEmpty());
        assertTrue(execution.taskRuns().stream().allMatch(t -> t.nextRetryAt() == null));
        assertEquals(1, fixture.executionService().executions(fixture.session()).size());
        assertTrue(fixture.executionService().executions(fixture.session()).stream().allMatch(Execution::isTerminal));
        assertTrue(fixture.pausedTaskRuns().isEmpty());
    }
    /**
     * @param business 外部业务端点
     * @param retry 策略，null 表示无策略
     * @param parallel 是否使用两个并行分支
     * @return 独立流程 YAML
     */
    private String yaml(Business business, String retry, boolean parallel) {
        String target = step(business, "business", "ready", retry);
        if (parallel) return "key: uc18-flow\ntasks:\n  - key: branches\n    type: org.cses.flow.extensions.flow.Parallel\n    tasks:\n" + (target + step(business, "side", "ready", null)).indent(4)
            + step(business, "after", "{{ outputs.business.value }}+{{ outputs.side.value }}", null);
        return "key: uc18-flow\ntasks:\n" + step(business, "before", "ready", null) + target + step(business, "after", "{{ outputs.business.value }}", null);
    }
    /**
     * @param business 外部业务端点
     * @param key 步骤标识
     * @param message 业务输入模板
     * @param retry 可空策略
     * @return 步骤 YAML
     */
    private String step(Business business, String key, String message, String retry) {
        return "  - key: " + key + "\n    type: " + RetryBusinessTask.class.getName() + "\n    endpoint: 'http://127.0.0.1:" + business.server.getAddress().getPort() + "/" + key + "'\n    message: '" + message + "'\n" + (retry == null ? "" : "    retry: " + retry + "\n");
    }
    /** 独立场景的外部系统，调用事实跨 Flow ApplicationContext 重启保留。 */
    private static class Business implements AutoCloseable {
        HttpServer server;
        java.util.concurrent.CountDownLatch release;
        List<Call> calls = new CopyOnWriteArrayList<>();
        /**
     * @param failures 业务步骤成功前失败次数
     * @throws Exception 本地端口无法绑定时抛出
     */
        Business(int failures) throws Exception { this(failures, false); }
        /**
     * @param failures 成功前失败次数
     * @param blocked 是否阻塞业务请求直到测试释放
     * @throws Exception 端口创建失败时抛出
     */
        Business(int failures, boolean blocked) throws Exception {
            release = new java.util.concurrent.CountDownLatch(blocked ? 1 : 0);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String key = exchange.getRequestURI().getPath().substring(1);
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                calls.add(Call.from(key, body, exchange.getRequestHeaders().getFirst("X-Execution-Id"), exchange.getRequestHeaders().getFirst("X-TaskRun-Id"), TimeUtil.now()));
                int number = calls(key).size();
                if (key.equals("business")) {
                    try { release.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException(interrupted); }
                }
                boolean fail = key.equals("business") && number <= failures;
                byte[] response = (fail ? "business-failure-" + number : body).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(fail ? 503 : 200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
                exchange.close();
            });
            server.start();
        }
        /**
     * @param key 步骤标识
     * @return 外部调用记录的不可变快照
     */
        List<Call> calls(String key) { return calls.stream().filter(c -> c.key().equals(key)).toList(); }
        /** 释放此场景的外部业务监听端口。 */
        @Override public void close() { release.countDown(); server.stop(0); }
    }
    /** 外部业务可查询的调用记录。 */
    private record Call(String key, String body, String executionId, String taskRunId, long at) {
        /**
     * @param key 步骤标识
     * @param body 请求内容
     * @param executionId 运行编号
     * @param taskRunId 步骤运行编号
     * @param at 请求到达毫秒时间
     * @return 不可变调用记录
     */
        static Call from(String key, String body, String executionId, String taskRunId, long at) { return new Call(key, body, executionId, taskRunId, at); }
    }
}
