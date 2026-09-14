package org.cses.flow.core.runner;

import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.extensions.log.Log;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 下一步只关联定义与对应运行记录，不额外携带状态计划。 */
class ResolvedNextTaskTest {
    /** 正确关联沿用对象，错误关联和缺少任一对象明确拒绝。 */
    @Test
    void retainsMatchingDefinitionAndRunWithoutAcceptingMismatchedIds() {
        Log task = Log.builder().id("step-id").key("step").build();
        TaskRun run = TaskRun.create(task.id(), null, Map.of());
        ResolvedNextTask resolved = ResolvedNextTask.from(task, run);
        assertSame(task, resolved.task());
        assertSame(run, resolved.taskRun());
        assertThrows(IllegalArgumentException.class,
            () -> ResolvedNextTask.from(task, TaskRun.create("other-id", null, Map.of())));
        assertThrows(NullPointerException.class, () -> ResolvedNextTask.from(null, run));
        assertThrows(NullPointerException.class, () -> ResolvedNextTask.from(task, null));
    }
}
