package org.cses.flow.core.services.executions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.extensions.flow.Branch;
import org.cses.flow.extensions.flow.Loop;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.extensions.flow.Parallel;
import org.cses.flow.extensions.flow.Route;

/** Definition ancestry determines causality; append order cannot order parallel branches. */
public class RewindPath {

    /**
     * Prevents construction; path calculations have no instance state.
     */
    private RewindPath() {
    }

    /**
     * Tests serial definition causality while keeping parallel siblings unordered.
     *
     * @param flow definition tree to read
     * @param beforeTaskId candidate predecessor definition ID
     * @param afterTaskId candidate successor definition ID
     * @return true for an earlier serial branch; false for equal, missing or unordered definitions
     */
    public static boolean precedes(Flow flow, String beforeTaskId, String afterTaskId) {
        return precedes(flow, beforeTaskId, afterTaskId, false);
    }

    /**
     * Tests predecessor order while isolating siblings of a conditional group.
     *
     * @param flow definition tree to read
     * @param beforeTaskId candidate predecessor definition ID
     * @param afterTaskId candidate successor definition ID
     * @return true for an earlier serial branch; false for equal, missing or unordered definitions
     */
    public static boolean precedesInBranch(Flow flow, String beforeTaskId, String afterTaskId) {
        return precedes(flow, beforeTaskId, afterTaskId, true);
    }

    /**
     * 比较两个定义首次分叉的祖先，不修改流程，并排除并行兄弟的先后关系。
     *
     * @param flow 只读流程定义树，非 null
     * @param beforeTaskId 候选前置定义编号，非 null
     * @param afterTaskId 候选后置定义编号，非 null
     * @param isolateRoutes 为 true 时隔离条件兄弟分支，为 false 时使用声明顺序
     * @return 存在串行先后关系时为 true，相同、缺失或无序时为 false
     */
    private static boolean precedes(Flow flow, String beforeTaskId, String afterTaskId, boolean isolateRoutes) {
        List<Task> before = path(flow.tasks(), beforeTaskId);
        List<Task> after = path(flow.tasks(), afterTaskId);
        int common = 0;
        while (common < before.size() && common < after.size()
                && before.get(common).id().equals(after.get(common).id())) {
            common++;
        }
        if (common == before.size() || common == after.size()) {
            return false;
        }
        Task parent = common == 0 ? null : before.get(common - 1);
        if (parent instanceof Parallel) {
            return false;
        }
        List<Task> siblings = parent == null ? flow.tasks() : parent.definitionChildren();
        if (isolateRoutes && parent != null && siblings.stream().allMatch(Route.class::isInstance)) {
            return false;
        }
        return siblings.indexOf(before.get(common)) < siblings.indexOf(after.get(common));
    }

    /**
     * 校验退回端点不位于循环体且祖先均拥有可恢复的 Branch 子作用域。
     *
     * @param flow 只读流程定义树，非 null
     * @param run 要校验的源或目标运行节点，非 null
     * @throws WorkflowException 端点属于循环或不支持的祖先作用域时抛出
     */
    public static void requireNonIterated(Flow flow, TaskRun run) {
        List<Task> ancestry = path(flow.tasks(), run.taskId());
        if (ancestry.stream().anyMatch(task ->
                task instanceof Loop || task instanceof LoopUntil)) {
            throw new WorkflowException("Rewind endpoints must be outside iterative scopes");
        }
        for (int index = 0; index < ancestry.size() - 1; index++) {
            if (!(ancestry.get(index) instanceof Branch<?>)) {
                throw new WorkflowException("Rewind endpoint ancestors must hold their child scopes");
            }
        }
    }

    /**
     * Collects effective target descendants and causal successors in reverse execution order.
     *
     * @param flow definition tree to read
     * @param execution snapshot to read without mutation
     * @param targetId completed target occurrence ID
     * @return immutable occurrence IDs; unrelated parallel branches are excluded
     * @throws WorkflowException when the target occurrence does not exist
     */
    public static List<String> affectedTaskRunIds(Flow flow, Execution execution, String targetId) {
        TaskRun target = execution.requireTaskRun(targetId);
        List<TaskRun> affected = execution.effectiveTaskRuns().stream()
                .filter(run -> path(flow.tasks(), run.taskId()).stream()
                        .anyMatch(task -> task.id().equals(target.taskId()))
                        || precedes(flow, target.taskId(), run.taskId()))
                .toList();
        List<String> ids = new ArrayList<>(affected.stream().map(TaskRun::id).toList());
        Collections.reverse(ids);
        return List.copyOf(ids);
    }

    /**
     * Finds the root-to-definition ancestry without changing task definitions.
     *
     * @param tasks ordered scope to search recursively
     * @param taskId definition ID to locate
     * @return new ancestry list, or an empty list when absent
     */
    private static List<Task> path(List<Task> tasks, String taskId) {
        for (Task task : tasks) {
            if (task.identifiedBy(taskId)) {
                return List.of(task);
            }
            List<Task> nested = path(task.definitionChildren(), taskId);
            if (!nested.isEmpty()) {
                List<Task> result = new ArrayList<>();
                result.add(task);
                result.addAll(nested);
                return result;
            }
        }
        return List.of();
    }
}
