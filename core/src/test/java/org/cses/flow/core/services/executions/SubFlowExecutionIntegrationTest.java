package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 子调用启动与同批 Worker 投递的真实集成回归，不增加 UC 场景。 */
class SubFlowExecutionIntegrationTest {
    /** 并行投递两个子调用与一个日志 Worker，核对三分支及汇聚全部完成。 */
    @Test
    void completesTwoSubFlowsAndWorkerInSameParallelBatch() {
        try (var fixture = WorkflowUcFixture.open()) {
            Flow child = fixture.deploy("""
                key: subflow-parallel-child
                tasks:
                  - key: child-log
                    type: org.cses.flow.extensions.log.Log
                    message: child completed
                """);
            Flow parent = fixture.deploy("""
                key: subflow-parallel-parent
                tasks:
                  - key: parallel
                    type: org.cses.flow.extensions.flow.Parallel
                    tasks:
                      - key: first
                        type: org.cses.flow.extensions.flow.SubFlow
                        flow:
                          key: %s
                          version: %s
                      - key: second
                        type: org.cses.flow.extensions.flow.SubFlow
                        flow:
                          key: %s
                          version: %s
                      - key: sibling-log
                        type: org.cses.flow.extensions.log.Log
                        message: sibling completed
                  - key: after
                    type: org.cses.flow.extensions.log.Log
                    message: joined
                """.formatted(child.key(), child.version(), child.key(), child.version()));
            Execution completed = fixture.startAndAwait(parent);
            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(State.Type.SUCCESS, run(completed, parent, "sibling-log").state().current());
            assertEquals(State.Type.SUCCESS, run(completed, parent, "after").state().current());
            TaskRun first = run(completed, parent, "first");
            TaskRun second = run(completed, parent, "second");
            assertEquals(State.Type.SUCCESS, first.state().current());
            assertEquals(State.Type.SUCCESS, second.state().current());
            Set<Object> childIds = Set.of(first.outputs().get("executionId"), second.outputs().get("executionId"));
            assertEquals(2, childIds.size());
            var children = fixture.executionService().executions(fixture.session()).stream()
                .filter(e -> completed.id().equals(e.origin().parentId())).toList();
            assertEquals(childIds, children.stream().map(Execution::id).collect(Collectors.toSet()));
            assertEquals(Set.of(first.id(), second.id()), children.stream().map(Execution::parentTaskRunId).collect(Collectors.toSet()));
            children.forEach(e -> assertEquals(State.Type.SUCCESS, e.state().current()));
            fixture.executionService().executions(fixture.session()).forEach(e -> assertTrue(e.unfinishedTaskRuns().isEmpty()));
            assertTrue(fixture.pausedTaskRuns().isEmpty());
        }
    }

    /**
     * 通过公开定义和快照查找指定步骤记录。
     * @param execution 当前运行快照
     * @param flow 已发布定义
     * @param key 步骤业务 key
     * @return 本场景唯一记录
     */
    private static TaskRun run(Execution execution, Flow flow, String key) {
        String id = flow.allTasks().stream().filter(task -> task.key().equals(key)).findFirst().orElseThrow().id();
        return execution.taskRunsForTask(id).getFirst();
    }
}
