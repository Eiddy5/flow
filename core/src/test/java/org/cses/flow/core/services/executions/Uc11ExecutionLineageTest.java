package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.Generation;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** UC: docs/uc/flow/UC-11 用户追溯退回后的 Flow 运行来源.md */
class Uc11ExecutionLineageTest {

    /** 连续退回 E1、E2，在真实上下文重建后追溯三实例并完成 E3。 */
    @Test
    void s1TracesDirectParentRootAndActualHistoryAcrossTwoReplays() {
        try (WorkflowUcFixture fixture = WorkflowUcFixture.open()) {
            Flow flow = fixture.deploy(yaml());
            var created = fixture.executionService().create(fixture.session(), flow.key(),
                Optional.of(flow.version()), Map.of("request", "lineage-request"));
            String e1Id = created.getExecutionId();
            Execution e1 = fixture.awaitStable(e1Id);
            TaskRun prepare = latest(e1, flow, "prepare");
            TaskRun target1 = latest(e1, flow, "target");
            var pause1 = fixture.waitingForExecution(e1Id);
            assertNull(e1.origin().parentId());
            assertEquals(e1Id, e1.origin().originId());
            assertTrue(e1.inheritedTaskRuns().isEmpty());
            assertEquals(List.of("prepare", "target", "approval", "open-approval"), keys(e1.ownTaskRuns(), flow));
            assertEquals(Map.of("request", "lineage-request"), e1.inputs());

            var first = fixture.rewind(pause1, target1.id(), "first correction");
            String e2Id = first.accepted().executionId();
            assertNotEquals(e1Id, e2Id);
            assertSnapshot(e1, first.accepted().sourceExecution());
            Execution stopped1 = query(fixture, e1Id);
            assertEquals(State.Type.KILLED, stopped1.state().current());
            assertHistoricalFacts(e1, stopped1);

            fixture.restartServer();
            Execution e2 = query(fixture, e2Id);
            assertEquals(e1Id, e2.origin().parentId());
            assertEquals(e1Id, e2.origin().originId());
            assertEquals(e1.inputs(), e2.inputs());
            assertEquals(e1.flowVersion(), e2.flowVersion());
            assertEquals(List.of("target", "approval", "open-approval"), keys(e2.ownTaskRuns(), flow));
            assertEquals(ids(e1.taskRuns()), ids(e2.inheritedTaskRuns()));
            assertEquals(prepare.id(), latest(e2, flow, "prepare").id());
            assertEquals(prepare.outputs(), latest(e2, flow, "prepare").outputs());
            TaskRun target2 = latest(e2, flow, "target");
            assertNotEquals(target1.id(), target2.id());
            assertNotEquals(target1.outputs(), target2.outputs());
            assertReason(e2.generation().current().orElseThrow(), pause1.taskRunId(), target1.id(), "first correction");

            var pause2 = fixture.waitingForExecution(e2Id);
            var second = fixture.rewind(pause2, target2.id(), "second correction");
            String e3Id = second.accepted().executionId();
            assertEquals(3, Set.of(e1Id, e2Id, e3Id).size());
            assertSnapshot(e2, second.accepted().sourceExecution());
            Execution stopped2 = query(fixture, e2Id);
            assertEquals(State.Type.KILLED, stopped2.state().current());
            assertHistoricalFacts(e2, stopped2);

            // 只保留公开编号，重新创建真实服务上下文并由 lineage 还原路径。
            fixture.restartServer();
            var lineage = fixture.executionService().lineage(fixture.session(), e3Id);
            assertEquals(Set.of(e1Id, e2Id, e3Id), lineage.stream().map(Execution::id).collect(Collectors.toSet()));
            Execution e3 = query(fixture, e3Id);
            assertEquals(e2Id, e3.origin().parentId());
            assertEquals(e1Id, e3.origin().originId());
            assertEquals(e2.inputs(), e3.inputs());
            assertEquals(ids(e2.taskRuns()), ids(e3.inheritedTaskRuns()));
            assertEquals(prepare.id(), latest(e3, flow, "prepare").id());
            assertEquals(prepare.outputs(), latest(e3, flow, "prepare").outputs());
            assertEquals(1, e3.taskRunsForTask(prepare.taskId()).size());
            assertEquals(List.of("target", "approval", "open-approval"), keys(e3.ownTaskRuns(), flow));
            assertFalse(keys(e3.taskRuns(), flow).contains("record"));
            TaskRun target3 = latest(e3, flow, "target");
            assertEquals(3, Set.of(target1.id(), target2.id(), target3.id()).size());
            assertEquals(List.of(target1.id(), target2.id(), target3.id()),
                e3.taskRunsForTask(target1.taskId()).stream().map(run -> run.outputs().get("token")).toList());
            assertReason(e3.generation().history().currents().getFirst(), pause1.taskRunId(), target1.id(), "first correction");
            assertReason(e3.generation().current().orElseThrow(), pause2.taskRunId(), target2.id(), "second correction");
            assertTrue(e3.generation().history().currents().getFirst().date()
                <= e3.generation().current().orElseThrow().date());
            assertEquals(prepare.id(), e3.effectiveTaskRuns().getFirst().id());
            assertFalse(ids(e3.effectiveTaskRuns()).contains(target1.id()));
            assertFalse(ids(e3.effectiveTaskRuns()).contains(target2.id()));
            assertTrue(ids(e3.effectiveTaskRuns()).contains(target3.id()));

            var pause3 = fixture.waitingForExecution(e3Id);
            fixture.resume(pause3, Map.of("decision", "APPROVED"));
            fixture.restartServer();
            Execution completed = query(fixture, e3Id);
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(List.of("target", "approval", "open-approval", "record"), keys(completed.ownTaskRuns(), flow));
            Map<?, ?> observed = (Map<?, ?>) latest(completed, flow, "record").inputs().get("outputs");
            assertEquals(Map.of("token", target3.id()), observed.get("target"));
            assertEquals(Map.of("decision", "APPROVED"), observed.get("approval"));
            assertSnapshot(stopped1, query(fixture, e1Id));
            assertSnapshot(stopped2, query(fixture, e2Id));
            assertEquals(2, completed.generation().history().currents().size());
            assertTrue(completed.generation().current().isEmpty());
            for (String id : List.of(e1Id, e2Id, e3Id)) {
                var finalLineage = fixture.executionService().lineage(fixture.session(), id);
                assertEquals(3, finalLineage.size());
                assertTrue(finalLineage.stream().allMatch(Execution::isTerminal));
                assertTrue(finalLineage.stream().allMatch(member -> member.unfinishedTaskRuns().isEmpty()));
            }
            assertEquals(e2Id, completed.origin().parentId());
            assertEquals(e1Id, completed.origin().originId());
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    /**
     * 读取用户可见实例。
     * @param fixture 当前公开服务夹具
     * @param id 用户取得的运行编号
     * @return 对应独立快照
     */
    private static Execution query(WorkflowUcFixture fixture, String id) {
        return fixture.executionService().execution(fixture.session(), id).orElseThrow();
    }

    /**
     * 根据定义业务标识读取最新真实运行记录。
     * @param execution 查询快照
     * @param flow 已发布定义
     * @param key 步骤业务标识
     * @return 最后一次实际发生的记录
     */
    private static TaskRun latest(Execution execution, Flow flow, String key) {
        String taskId = flow.allTasks().stream().filter(task -> task.key().equals(key)).findFirst().orElseThrow().id();
        return execution.taskRunsForTask(taskId).getLast();
    }

    /**
     * 提取运行顺序对应的业务路径。
     * @param runs 有序运行记录
     * @param flow 已发布定义
     * @return 步骤业务标识序列
     */
    private static List<String> keys(List<TaskRun> runs, Flow flow) {
        return runs.stream().map(run -> flow.findTask(run.taskId()).orElseThrow().key()).toList();
    }

    /**
     * 提取稳定编号序列。
     * @param runs 有序运行记录
     * @return 未改变的编号序列
     */
    private static List<String> ids(List<TaskRun> runs) {
        return runs.stream().map(TaskRun::id).toList();
    }

    /**
     * 检查退回坐标和用户原因。
     * @param current 查询所得退回记录
     * @param source 用户选择的源 Pause
     * @param target 用户选择的目标记录
     * @param reason 用户输入原因
     */
    private static void assertReason(Generation.Current current, String source, String target, String reason) {
        assertEquals(source, current.sourceTaskRunId().orElseThrow());
        assertEquals(target, current.targetTaskRunId().orElseThrow());
        assertEquals(reason, current.reason());
    }

    /**
     * 检查旧输入输出及状态前缀仍保留，允许追加停止事实。
     * @param before 原查询快照
     * @param after 停止后查询快照
     */
    private static void assertHistoricalFacts(Execution before, Execution after) {
        assertEquals(before.inputs(), after.inputs());
        assertEquals(ids(before.taskRuns()), ids(after.taskRuns()));
        assertTrue(after.state().history().containsAll(before.state().history()));
        before.taskRuns().forEach(run -> {
            TaskRun retained = after.requireTaskRun(run.id());
            assertEquals(run.inputs(), retained.inputs());
            assertEquals(run.outputs(), retained.outputs());
            assertTrue(retained.state().history().containsAll(run.state().history()));
        });
    }

    /**
     * 检查重查后的完整快照不被另一实例的推进改写。
     * @param before 原查询快照
     * @param after 当前查询快照
     */
    private static void assertSnapshot(Execution before, Execution after) {
        assertHistoricalFacts(before, after);
        assertEquals(before.id(), after.id());
        assertEquals(before.origin(), after.origin());
        assertEquals(before.state(), after.state());
        assertEquals(before.generation().current(), after.generation().current());
        assertEquals(before.generation().history().currents(), after.generation().history().currents());
        before.taskRuns().forEach(run -> assertEquals(run.state(), after.requireTaskRun(run.id()).state()));
    }

    /** @return 结果可由真实 TaskRun 编号区分的最小验证流程 */
    private static String yaml() {
        return """
            key: uc11-s1-flow
            inputs:
              - key: request
                type: STRING
                required: true
            tasks:
              - key: prepare
                type: %s
              - key: target
                type: %s
              - key: approval
                type: org.cses.flow.extensions.flow.Pause
                onPause:
                  key: open-approval
                  type: org.cses.flow.extensions.log.Log
                  message: open approval
                onResume:
                  - key: decision
                    type: STRING
              - key: record
                type: org.cses.flow.extensions.log.Log
                message: 'target={{ outputs.target.token }}; approval={{ outputs.approval.decision }}'
            """.formatted(Uc04PauseFlowTest.RewindProbeTask.class.getCanonicalName(),
                Uc04PauseFlowTest.RewindProbeTask.class.getCanonicalName());
    }
}
