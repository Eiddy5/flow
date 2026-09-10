package org.cses.flow.core.services.executions;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.extensions.log.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** UC: docs/uc/flow/UC-13 用户调用并追溯子流程.md */
class Uc13SubFlowTest {

    /** 发布父子流程，查询并恢复子 Pause，验证传参、来源、结果消费及双终态。 */
    @Test
    void s1ResumesChildPauseAndReturnsResultToWaitingParent() {
        try (var logs = new Logs(); var fixture = WorkflowUcFixture.open()) {
            Flow child = fixture.deploy("""
                key: uc13-s1-child
                inputs:
                  - key: request
                    type: STRING
                    required: true
                tasks:
                  - key: wait
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: opened
                      type: org.cses.flow.extensions.log.Log
                      message: 'child received {{ inputs.request }}'
                    onResume:
                      - key: approved
                        type: BOOLEAN
                        required: true
                  - key: returned
                    type: org.cses.flow.extensions.log.Log
                    message: 'child result {{ inputs.request }} {{ outputs.wait.approved }}'
                """);
            Flow parent = fixture.deploy(parentYaml("uc13-s1-parent", child,
                "parent result {{ inputs.request }} {{ outputs.call.outputs.wait.approved }}"));
            String parentId = start(fixture, parent, Map.of("request", "request-s1"));
            Execution running = fixture.awaitExecution(fixture.session(), parentId,
                e -> fixture.executionService().executions(fixture.session()).size() == 2);
            Execution childRun = children(fixture, parentId).getFirst();
            var pause = fixture.waitingForExecution(childRun.id());
            running = query(fixture, parentId);
            assertEquals(State.Type.RUNNING, run(running, parent, "call").state().current());
            assertTrue(running.taskRunsForTask(taskId(parent, "after")).isEmpty());
            assertEquals(Map.of("request", "request-s1"), childRun.inputs());
            assertEquals(Map.of("request", "request-s1"), run(running, parent, "call").inputs());
            assertNotEquals(parentId, childRun.id());
            assertOrigin(childRun, parentId, parentId);
            fixture.resume(pause, Map.of("approved", true));
            Execution completed = fixture.awaitStable(parentId);
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(State.Type.SUCCESS, run(completed, parent, "call").state().current());
            assertEquals(childRun.id(), run(completed, parent, "call").outputs().get("executionId"));
            assertEquals(Map.of("approved", true), ((Map<?, ?>) run(completed, parent, "call").outputs().get("outputs")).get("wait"));
            assertEquals(State.Type.SUCCESS, run(completed, parent, "after").state().current());
            assertTrue(logs.messages().containsAll(List.of("child received request-s1", "child result request-s1 true", "parent result request-s1 true")));
            assertOrigin(query(fixture, childRun.id()), parentId, parentId);
            assertFinished(fixture, 2, State.Type.SUCCESS);
        }
    }

    /** 发布三层独立流程，验证逐层输入、返回消费与可重查的直接和根来源。 */
    @Test
    void s2TracesNestedCallsAndConsumesResultsAtEachLevel() {
        try (var logs = new Logs(); var fixture = WorkflowUcFixture.open()) {
            Flow leaf = fixture.deploy("""
                key: uc13-s2-leaf
                inputs:
                  - key: request
                    type: STRING
                tasks:
                  - key: received
                    type: org.cses.flow.extensions.log.Log
                    message: 'leaf {{ inputs.request }}'
                  - key: produce
                    type: %s
                """.formatted(Uc08DynamicLogFlowTest.LogInputTask.class.getCanonicalName()));
            Flow middle = fixture.deploy(parentYaml("uc13-s2-middle", leaf,
                "middle {{ inputs.request }} {{ outputs.call.outputs.produce.result }}"));
            Flow root = fixture.deploy(parentYaml("uc13-s2-root", middle,
                "root {{ inputs.request }} {{ outputs.call.outputs.call.outputs.produce.result }}"));
            String rootId = start(fixture, root, Map.of("request", "request-s2"));
            fixture.awaitStable(rootId);
            Execution middleRun = children(fixture, rootId).getFirst();
            Execution leafRun = children(fixture, middleRun.id()).getFirst();
            assertEquals(3, Set.of(rootId, middleRun.id(), leafRun.id()).size());
            assertOrigin(middleRun, rootId, rootId);
            assertOrigin(leafRun, middleRun.id(), rootId);
            for (Execution execution : fixture.executionService().lineage(fixture.session(), leafRun.id())) {
                assertEquals(Map.of("request", "request-s2"), query(fixture, execution.id()).inputs());
            }
            assertEquals(3, fixture.executionService().lineage(fixture.session(), leafRun.id()).size());
            assertEquals("ready", run(leafRun, leaf, "produce").outputs().get("result"));
            assertEquals(leafRun.id(), run(middleRun, middle, "call").outputs().get("executionId"));
            assertEquals(middleRun.id(), run(query(fixture, rootId), root, "call").outputs().get("executionId"));
            assertTrue(logs.messages().containsAll(List.of("leaf request-s2", "middle request-s2 ready", "root request-s2 ready")));
            assertFinished(fixture, 3, State.Type.SUCCESS);
        }
    }

    /**
     * 分别传递缺失值和非法数字，验证父调用失败且没有接纳子业务运行。
     * @param invalidType true 传非法数字，false 缺少必填值
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void s3RejectsMissingAndInvalidChildInputs(boolean invalidType) {
        try (var fixture = WorkflowUcFixture.open()) {
            Flow child = fixture.deploy(simpleChild("uc13-s3-child", "INTEGER", "received {{ inputs.request }}"));
            Flow parent = fixture.deploy(parentYaml("uc13-s3-parent", child, "must not execute"));
            String id = start(fixture, parent, invalidType ? Map.of("request", "not-a-number") : Map.of());
            Execution failed = fixture.awaitStable(id);
            assertFailure(failed, parent);
            assertTrue(run(failed, parent, "call").error().orElseThrow().toLowerCase().contains("request"));
            assertTrue(children(fixture, id).isEmpty());
            assertFinished(fixture, 1, State.Type.FAILED);
        }
    }

    /** 删除已引用子定义后启动父流程，验证明确失败且不产生子运行或后置执行。 */
    @Test
    void s4FailsWhenReferencedChildDefinitionWasDeleted() {
        try (var fixture = WorkflowUcFixture.open()) {
            Flow child = fixture.deploy(simpleChild("uc13-s4-child", "STRING", "received {{ inputs.request }}"));
            Flow parent = fixture.deploy(parentYaml("uc13-s4-parent", child, "must not execute"));
            fixture.flowService().delete(fixture.session(), child.key(), false);
            assertTrue(fixture.flowService().latestFlow(fixture.session(), child.key()).isEmpty());
            String id = start(fixture, parent, Map.of("request", "request-s4"));
            Execution failed = fixture.awaitStable(id);
            assertFailure(failed, parent);
            assertTrue(run(failed, parent, "call").error().orElseThrow().contains(child.key()));
            assertTrue(children(fixture, id).isEmpty());
            assertFinished(fixture, 1, State.Type.FAILED);
        }
    }

    /** 以缺失运行路径使真实子 Worker 失败，验证失败记录、来源及父调用失败收敛。 */
    @Test
    void s5PropagatesChildBusinessFailureWithoutSuccessResult() {
        try (var fixture = WorkflowUcFixture.open()) {
            Flow child = fixture.deploy(simpleChild("uc13-s5-child", "STRING", "{{ inputs.missing }}"));
            Flow parent = fixture.deploy(parentYaml("uc13-s5-parent", child, "must not execute"));
            String id = start(fixture, parent, Map.of("request", "request-s5"));
            assertFailure(fixture.awaitStable(id), parent);
            Execution childRun = children(fixture, id).getFirst();
            assertOrigin(childRun, id, id);
            assertEquals(Map.of("request", "request-s5"), childRun.inputs());
            assertTrue(run(childRun, child, "business").error().orElseThrow().contains("missing"));
            assertTrue(run(childRun, child, "business").outputs().isEmpty());
            assertFinished(fixture, 2, State.Type.FAILED);
        }
    }

    /**
     * 通过公开服务启动指定版本。
     * @param fixture 当前真实装配
     * @param flow 已发布流程
     * @param inputs 本次用户输入，不修改
     * @return 用户可见运行编号
     */
    private static String start(WorkflowUcFixture fixture, Flow flow, Map<String, ?> inputs) {
        return fixture.executionService().create(fixture.session(), flow.key(), Optional.of(flow.version()), inputs).getExecutionId();
    }

    /**
     * 重新查询运行快照。
     * @param fixture 当前真实装配
     * @param id 用户可见编号
     * @return 当前独立快照
     */
    private static Execution query(WorkflowUcFixture fixture, String id) {
        return fixture.executionService().execution(fixture.session(), id).orElseThrow();
    }

    /**
     * 查询直接子运行。
     * @param fixture 当前真实装配
     * @param parentId 父运行编号
     * @return 公开查询中的直接子运行列表
     */
    private static List<Execution> children(WorkflowUcFixture fixture, String parentId) {
        return fixture.executionService().executions(fixture.session()).stream()
            .filter(e -> parentId.equals(e.origin().parentId())).toList();
    }

    /**
     * 根据步骤业务 key 读取记录。
     * @param execution 公开运行快照
     * @param flow 已发布定义
     * @param key 步骤业务 key
     * @return 本场景唯一的步骤记录
     */
    private static TaskRun run(Execution execution, Flow flow, String key) {
        return execution.taskRunsForTask(taskId(flow, key)).getFirst();
    }

    /**
     * 从公开定义解析步骤编号。
     * @param flow 已发布定义
     * @param key 步骤业务 key
     * @return 定义中的技术编号
     */
    private static String taskId(Flow flow, String key) {
        return flow.allTasks().stream().filter(task -> task.key().equals(key)).findFirst().orElseThrow().id();
    }

    /**
     * 核对用户可查询的来源。
     * @param execution 子运行快照
     * @param parentId 预期直接来源
     * @param rootId 预期最初来源
     */
    private static void assertOrigin(Execution execution, String parentId, String rootId) {
        assertEquals(parentId, execution.origin().parentId());
        assertEquals(rootId, execution.origin().originId());
    }

    /**
     * 核对失败且后置步骤未执行。
     * @param execution 父运行快照
     * @param flow 父定义
     */
    private static void assertFailure(Execution execution, Flow flow) {
        assertEquals(State.Type.FAILED, execution.state().current());
        TaskRun call = run(execution, flow, "call");
        assertEquals(State.Type.FAILED, call.state().current());
        assertTrue(call.error().isPresent());
        assertTrue(call.outputs().isEmpty());
        assertTrue(execution.taskRunsForTask(taskId(flow, "after")).isEmpty());
    }

    /**
     * 重查所有运行，核对数量、终态与零活动资源。
     * @param fixture 当前真实装配
     * @param count 预期运行数量
     * @param state 全部运行预期终态
     */
    private static void assertFinished(WorkflowUcFixture fixture, int count, State.Type state) {
        List<Execution> executions = fixture.executionService().executions(fixture.session());
        assertEquals(count, executions.size());
        executions.forEach(e -> {
            assertEquals(state, e.state().current());
            assertTrue(e.unfinishedTaskRuns().isEmpty());
            assertTrue(e.pausedTaskRuns().isEmpty());
        });
        assertTrue(fixture.pausedTaskRuns().isEmpty());
        System.out.println("UC13 final executions=" + count + ", active=0, waiting=0");
    }

    /**
     * 生成沿用标准 Input 列表的父流程。
     * @param key 父业务 key
     * @param child 已发布子流程
     * @param message 后置日志模板
     * @return 待公开发布的 YAML
     */
    private static String parentYaml(String key, Flow child, String message) {
        return """
            key: %s
            inputs:
              - key: request
                type: STRING
            tasks:
              - key: call
                type: org.cses.flow.extensions.flow.SubFlow
                flow:
                  key: %s
                  version: %s
                inputs:
                  - key: request
                    type: STRING
              - key: after
                type: org.cses.flow.extensions.log.Log
                message: '%s'
            """.formatted(key, child.key(), child.version(), message);
    }

    /**
     * 生成要求必填输入且通过日志观察结果的子流程。
     * @param key 子业务 key
     * @param type 输入类型
     * @param message 业务日志模板
     * @return 待公开发布的 YAML
     */
    private static String simpleChild(String key, String type, String message) {
        return """
            key: %s
            inputs:
              - key: request
                type: %s
                required: true
            tasks:
              - key: business
                type: org.cses.flow.extensions.log.Log
                message: '%s'
            """.formatted(key, type, message);
    }

    /** 捕获真实 Log Worker 的用户日志，不替换任务执行。 */
    private static class Logs implements AutoCloseable {
        private ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Log.class);
        private ListAppender<ILoggingEvent> appender = new ListAppender<>();

        /** 注册本场景日志收集器。 */
        private Logs() {
            appender.start();
            logger.addAppender(appender);
        }

        /** @return 已产生的格式化日志副本 */
        private List<String> messages() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        /** 移除本场景日志收集器。 */
        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
