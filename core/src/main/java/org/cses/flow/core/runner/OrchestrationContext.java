package org.cses.flow.core.runner;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 只读编排搜索：复用有效运行选择、顺序输出传递和并行隔离，不应用状态或轮次变化。 */
public class OrchestrationContext {
    private Flow flow;
    private Execution execution;
    private Task task;
    private TaskRun taskRun;

    /**
     * 绑定当前搜索节点；内部只读取传入的定义和运行事实。
     * @param flow 精确版本定义，非 null
     * @param execution 当前运行快照，非 null
     * @param task 当前定义，根搜索时为 null
     * @param taskRun 当前节点，根搜索时为 null
     */
    private OrchestrationContext(Flow flow, Execution execution, Task task, TaskRun taskRun) {
        this.flow = flow;
        this.execution = execution;
        this.task = task;
        this.taskRun = taskRun;
    }

    /**
     * 为指定编排节点创建只读搜索上下文。
     * @param flow 精确版本定义，非 null
     * @param execution 当前运行快照，非 null
     * @param task 当前节点定义，非 null
     * @param taskRun 当前运行记录，非 null
     * @return 不提供运行修改入口的搜索上下文
     */
    public static OrchestrationContext from(Flow flow, Execution execution, Task task, TaskRun taskRun) {
        return new OrchestrationContext(java.util.Objects.requireNonNull(flow), java.util.Objects.requireNonNull(execution),
                java.util.Objects.requireNonNull(task), java.util.Objects.requireNonNull(taskRun));
    }

    /**
     * 创建根作用域的只读上下文，供 Executor 解析顶层任务。
     * @param flow 精确版本定义，非 null
     * @param execution 当前运行快照，非 null
     * @return 根作用域上下文
     */
    public static OrchestrationContext root(Flow flow, Execution execution) {
        return new OrchestrationContext(java.util.Objects.requireNonNull(flow),
                java.util.Objects.requireNonNull(execution), null, null);
    }

    /**
     * 查询当前节点是否曾进入指定状态。
     * @param state 要查询的历史状态，非 null
     * @return 历史中存在该状态时为 true
     */
    public boolean hasReached(State.Type state) {
        return taskRun.state().history().stream().anyMatch(item -> item.state() == state);
    }

    /** @return 当前节点的只读输入，不暴露运行记录修改入口 */
    public Map<String, Object> inputs() {
        return taskRun.inputs();
    }

    /** @return 当前节点轮次，尚未开始轮次时为 0 */
    public int iteration() {
        return taskRun.generation().current().map(value -> value.version()).orElse(0);
    }

    /** @return 当前节点完整的只读运行变量，包含 inputs、outputs 和 vars */
    public Map<String, Object> variables() {
        return RunVariables.builder().flow(flow).execution(execution).task(task).taskRun(taskRun).build();
    }

    /**
     * 顺序解析直接子任务，只返回第一处尚未完成的可启动节点。
     * @param tasks 有序定义，只读
     * @param iteration 本轮编号，非循环为 null
     * @return 待启动节点；空列表可能表示等待或完成
     */
    public List<ResolvedNextTask> serial(List<Task> tasks, Integer iteration) {
        return serial(tasks, iteration, incomingOutputs());
    }

    /**
     * 使用相同输入快照解析各并行分支，不传递兄弟分支输出。
     * @param tasks 直接子任务，只读
     * @return 本轮可启动的直接子任务
     */
    public List<ResolvedNextTask> parallel(List<Task> tasks) {
        List<ResolvedNextTask> nexts = new ArrayList<>();
        for (Task child : tasks) {
            nexts.addAll(next(child, null, incomingOutputs()));
        }
        return List.copyOf(nexts);
    }

    /**
     * 查询指定轮次的直接子任务是否均已完成；不存在的节点不算完成。
     * @param tasks 直接子任务，只读
     * @param iteration 本轮编号，非循环为 null
     * @return 所有节点成功、警告或跳过时为 true，空作用域为 true
     */
    public boolean settled(List<Task> tasks, Integer iteration) {
        return tasks.stream().allMatch(child -> execution.taskRunForOccurrence(child.id(), parentId(), iteration)
                .filter(OrchestrationContext::settled).isPresent());
    }

    /**
     * 顺序解析子任务并传递已完成节点输出，不递归推进子作用域。
     * @param tasks 有序定义，只读
     * @param iteration 本轮编号，非循环为 null
     * @param initial 初始可见输出，只读
     * @return 第一处未完成节点的可启动记录，等待或完成时为空
     */
    public List<ResolvedNextTask> serial(List<Task> tasks, Integer iteration, Map<String, ?> initial) {
        Map<String, Object> visible = new LinkedHashMap<>(initial);
        for (Task child : tasks) {
            Optional<TaskRun> found = execution.taskRunForOccurrence(child.id(), parentId(), iteration);
            if (found.isEmpty() || !settled(found.orElseThrow())) return next(child, iteration, visible);
            TaskRun current = found.orElseThrow();
            if (!current.state().is(State.Type.SKIPPED)) visible.put(child.key(), current.outputs());
        }
        return List.of();
    }

    /**
     * 返回指定出现位置的待启动节点；不存在时生成尚未接纳的记录。
     * @param child 当前定义，只读
     * @param iteration 本轮编号，非循环为 null
     * @param visible 进入节点时的可见输出，只读
     * @return Task 与 TaskRun 对，已启动时为空；不修改聚合
     */
    private List<ResolvedNextTask> next(Task child, Integer iteration, Map<String, ?> visible) {
        Optional<TaskRun> found = execution.taskRunForOccurrence(child.id(), parentId(), iteration);
        if (found.isPresent()) {
            TaskRun current = found.orElseThrow();
            return current.state().is(State.Type.CREATED)
                    ? List.of(ResolvedNextTask.from(child, current)) : List.of();
        }
        Integer inherited = taskRun != null && taskRun.executionGenerationVersion().isPresent()
                ? taskRun.executionGenerationVersion().getAsInt() : null;
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (!visible.isEmpty()) inputs.put("outputs", Map.copyOf(visible));
        if (iteration != null) inputs.put("loop", Map.of("iteration", iteration));
        TaskRun next = TaskRun.create(child.id(), parentId(), Map.copyOf(inputs), iteration,
                execution.replayGenerationVersion(child.id(), parentId(), iteration, inherited));
        return List.of(ResolvedNextTask.from(child, next));
    }

    /**
     * 判断一个节点是否允许后续任务继续。
     * @param run 待读取的运行记录，非 null
     * @return 成功、警告或跳过时为 true
     */
    private static boolean settled(TaskRun run) {
        return run.state().is(State.Type.SUCCESS) || run.state().is(State.Type.WARNING)
                || run.state().is(State.Type.SKIPPED);
    }

    /** @return 当前父节点编号，根作用域为 null */
    private String parentId() {
        return taskRun == null ? null : taskRun.id();
    }

    /** @return 当前节点进入时继承的输出快照，不混入兄弟分支的结果 */
    private Map<String, Object> incomingOutputs() {
        Object incoming = taskRun == null ? null : taskRun.inputs().get("outputs");
        if (!(incoming instanceof Map<?, ?> values)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Map.copyOf(result);
    }
}
