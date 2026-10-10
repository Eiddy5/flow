package org.cses.flow.core.services.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 通过公开查询所得完整快照核对拒绝或隔离操作没有修改既有运行事实。 */
class ExecutionAssertions {
    /** 禁止实例化无状态测试断言。 */
    private ExecutionAssertions() {}

    /**
     * 比较运行身份、版本、输入、根历史及每个步骤的完整状态历史、输入输出与尝试事实。
     * @param before 操作前的公开查询快照，不修改
     * @param after 操作后的公开查询快照，不修改
     */
    static void unchanged(Execution before, Execution after) {
        assertEquals(before.id(), after.id());
        assertEquals(before.companyId(), after.companyId());
        assertEquals(before.flowKey(), after.flowKey());
        assertEquals(before.flowVersion(), after.flowVersion());
        assertEquals(before.inputs(), after.inputs());
        assertEquals(before.origin(), after.origin());
        assertEquals(before.generation().current(), after.generation().current());
        assertEquals(before.generation().history().currents(), after.generation().history().currents());
        assertEquals(before.state().current(), after.state().current());
        assertEquals(before.state().history(), after.state().history());
        assertEquals(before.taskRuns().stream().map(TaskRun::id).toList(), after.taskRuns().stream().map(TaskRun::id).toList());
        for (TaskRun expected : before.taskRuns()) {
            TaskRun actual = after.requireTaskRun(expected.id());
            assertEquals(expected.taskId(), actual.taskId());
            assertEquals(expected.parentId(), actual.parentId());
            assertEquals(expected.error(), actual.error());
            assertEquals(expected.state().current(), actual.state().current());
            assertEquals(expected.state().history(), actual.state().history());
            assertEquals(expected.inputs(), actual.inputs());
            assertEquals(expected.outputs(), actual.outputs());
            assertEquals(expected.nextRetryAt(), actual.nextRetryAt());
            assertEquals(expected.attemptNumber(), actual.attemptNumber());
            assertEquals(expected.attempts().stream().map(a -> java.util.Arrays.asList(a.number(), a.startedAt(), a.completedAt(), a.state(), a.error())).toList(),
                actual.attempts().stream().map(a -> java.util.Arrays.asList(a.number(), a.startedAt(), a.completedAt(), a.state(), a.error())).toList());
        }
    }
}
