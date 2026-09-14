package org.cses.flow.executor;

import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.ExecutableTask;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Output;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.TaskOutputs;
import org.cses.flow.core.runner.OrchestrationContext;
import org.cses.flow.core.runner.ResolvedNextTask;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.runner.RunVariables;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskResult;

/**
 * Internal state-machine implementation for one {@link ExecutorContext}.
 *
 * <p>{@link #process(ExecutorContext)} advances one deterministic scheduling
 * cycle. The caller owns persistence, Worker invocation, and any subsequent
 * cycle.</p>
 */
@Singleton
public class ExecutorService {

    /**
     * 推进一个调度周期，依次接纳任务、暂存调用并判断运行收敛。
     * @param context 当前执行上下文，非 null，会被修改；已有 Worker 任务须先消费
     * @return 同一执行上下文，包含本轮变化和待投递任务
     * @throws IllegalStateException 仍有未消费 Worker 任务时抛出
     */
    public ExecutorContext process(ExecutorContext context) {
        java.util.Objects.requireNonNull(context, "context");
        if (!context.workerTasks().isEmpty()) {
            throw new IllegalStateException("Pending WorkerTasks must be consumed before processing");
        }

        if (!context.canBeProcessed()) {
            return context;
        }

        context = this.handleRestart(context);
        handleKillingTaskRuns(context);
        handleKilling(context);

        boolean progressed = false;
        if (context.canScheduleNext()) {
            progressed = handleNext(context);
            handleWorkerTasks(context);
            handleOrchestrationTasks(context);
        }

        handleEnd(context, progressed);
        return context;
    }

    private ExecutorContext handleRestart(ExecutorContext executor) {
        if (!executor.execution().state().is(State.Type.RESTARTED)) {
            return executor;
        }
        return executor.restart(executor.execution().restart());
    }

    private static void handleKillingTaskRuns(ExecutorContext context) {
        if (!context.execution().state().is(State.Type.KILLING)) {
            return;
        }
        context.execution().killUnfinishedTaskRuns();
        context.captureState();
    }

    private static void handleKilling(ExecutorContext context) {
        Execution execution = context.execution();
        if (!execution.state().is(State.Type.KILLING) || !execution.unfinishedTaskRuns().isEmpty()) {
            return;
        }
        execution.finishKilling();
        context.captureState();
    }

    /**
     * 分别解析运行节点状态与下一步任务，再接纳待启动记录。
     * @param context 当前运行上下文，聚合和待启动列表会被修改
     * @return 本轮有新任务或节点状态变化时为 true
     * @throws IllegalStateException 待接纳任务或轮次不合法时抛出
     */
    private static boolean handleNext(ExecutorContext context) {
        Execution execution = context.execution();
        List<ResolvedNextTask> nexts = new ArrayList<>(OrchestrationContext.root(context.flow(), execution)
                .serial(context.flow().tasks(), null));
        boolean progressed = false;
        for (TaskRun run : execution.activeTaskRuns()) {
            Task task = requireTask(context, run);
            if (!run.state().is(State.Type.RUNNING) || !(task instanceof OrchestrationTask<?> orchestration)) continue;
            OrchestrationContext scope = OrchestrationContext.from(context.flow(), execution, task, run);
            try {
                var state = orchestration.resolveState(scope);
                if (state.isPresent()) {
                    completeOrchestrationScope(context, run.id(), state.orElseThrow());
                    progressed = true;
                } else {
                    nexts.addAll(orchestration.resolveNexts(scope));
                }
            } catch (RuntimeException error) {
                execution.failTaskRun(run.id(), error.getMessage() == null || error.getMessage().isBlank()
                        ? error.getClass().getSimpleName() : error.getMessage());
                context.captureState();
            }
            if (execution.isTerminal()) {
                context.clearNexts();
                return true;
            }
        }
        requireRuntimeCapabilities(nexts);
        List<TaskRun> unattached = nexts.stream().map(ResolvedNextTask::taskRun)
                .filter(run -> execution.findTaskRun(run.id()).isEmpty()).toList();
        if (execution.state().is(State.Type.CREATED)) {
            if (unattached.isEmpty()) execution.start();
            else execution.startWithTaskRuns(unattached);
            context.captureState();
        } else if (!unattached.isEmpty()) {
            for (TaskRun run : unattached) acceptIteration(context, run);
            execution.addTaskRuns(unattached);
            context.captureState();
        }
        context.stageNexts(nexts);
        return progressed || !nexts.isEmpty();
    }

    /**
     * 接纳循环子任务前更新父轮次；同轮其他任务不重复更新。
     * @param context 当前运行上下文，会修改父节点的轮次记录
     * @param next 尚未接纳的子任务，只读；无 iteration 时不处理
     * @throws IllegalStateException 轮次跳号或父节点缺失时抛出
     */
    private static void acceptIteration(ExecutorContext context, TaskRun next) {
        if (next.iteration().isEmpty()) return;
        Execution execution = context.execution();
        TaskRun parent = execution.requireTaskRun(next.parentId().orElseThrow());
        int current = parent.generation().current().map(value -> value.version()).orElse(0);
        int target = next.iteration().getAsInt();
        if (target == current) return;
        if (target != current + 1) throw new IllegalStateException("Loop iteration must be consecutive: " + target);
        if (current == 0) execution.startTaskRunGeneration(parent.id(), "INITIAL");
        else execution.advanceTaskRunGeneration(parent.id(), requireTask(context, parent) instanceof LoopUntil
                ? "CONDITION_NOT_SATISFIED" : "FIXED_COUNT_NOT_REACHED");
    }

    /**
     * 将待启动的 RunnableTask 转为 Worker 投递记录。
     * @param context 已接纳节点的执行上下文，会暂存 Worker 任务
     */
    private static void handleWorkerTasks(ExecutorContext context) {
        List<WorkerTask> workerTasks = new ArrayList<>();
        for (ResolvedNextTask next : context.nexts()) {
            TaskRun taskRun = next.taskRun();
            Task task = next.task();
            if (task instanceof RunnableTask) {
                workerTasks.add(workerTask(context, taskRun, task));
            }
        }
        context.stageWorkerTasks(workerTasks);
    }

    private static WorkerTask workerTask(
        ExecutorContext context,
        TaskRun taskRun,
        Task task
    ) {
        Map<String, Object> variables = RunVariables.builder()
            .flow(context.flow())
            .execution(context.execution())
            .task(task)
            .taskRun(taskRun)
            .build();
        return WorkerTask.from(
            context.execution().id(),
            taskRun.id(),
            taskRun.parentId().orElse(null),
            task,
            variables
        );
    }

    /**
     * 启动编排节点并暂存独立调用。
     * @param context 当前运行上下文，状态与待执行计划会被修改
     */
    private static void handleOrchestrationTasks(ExecutorContext context) {
        for (ResolvedNextTask next : context.nexts()) {
            TaskRun taskRun = next.taskRun();
            Task task = next.task();
            if (task instanceof ExecutableTask<?>) {
                context.stageSubFlow(taskRun.id());
            } else if (task instanceof OrchestrationTask) {
                handleOrchestration(context, taskRun);
            }
        }
        context.clearNexts();
    }

    public WorkerTask dispatch(
        ExecutorContext context,
        WorkerTask workerTask
    ) {
        requireExecution(context, workerTask.executionId());
        context.execution().startTaskRun(workerTask.taskRunId());
        context.captureState();
        TaskRun runningTaskRun = context.execution().requireTaskRun(
            workerTask.taskRunId()
        );
        Task task = requireTask(context, runningTaskRun);
        return workerTask(context, runningTaskRun, task);
    }

    /**
     * 启动已规划的编排任务；后续周期通过行为接口计算子任务与完成决定。
     * @param context 非 null 的当前执行上下文，会被修改
     * @param plannedTaskRun 非 null 的 CREATED 编排任务记录
     * @throws IllegalStateException 任务状态或编排能力非法时抛出
     */
    private static void handleOrchestration(ExecutorContext context, TaskRun plannedTaskRun) {
        TaskRun taskRun = context.execution().requireTaskRun(plannedTaskRun.id());
        if (!taskRun.state().is(State.Type.CREATED)) {
            throw new IllegalStateException("Only a CREATED Orchestration TaskRun can be handled: " + taskRun.id());
        }
        Task task = context.flow().findTask(taskRun.taskId()).orElseThrow(() -> new IllegalStateException("TaskRun references a missing Task: " + taskRun.taskId()));
        if (!(task instanceof OrchestrationTask<?>) || task instanceof RunnableTask) {
            throw new IllegalStateException("Orchestration handling requires only the " + "OrchestrationTask capability: " + task.getType());
        }

        context.execution().startTaskRun(taskRun.id());
        context.captureState();
    }

    /**
     * 应用具体编排任务产生的状态决定，保留已有恢复输出。
     * @param context 当前执行上下文，会被修改
     * @param taskRunId 非 null 的 RUNNING 节点编号
     * @param state 本轮解析的目标状态，不含业务输出
     * @throws IllegalStateException 当前节点不再运行时抛出
     */
    private static void completeOrchestrationScope(ExecutorContext context, String taskRunId, State.Type state) {
        Execution execution = context.execution();
        TaskRun run = execution.requireTaskRun(taskRunId);
        if (!run.state().is(State.Type.RUNNING)) throw new IllegalStateException("Orchestration must be RUNNING: " + taskRunId);
        if (state != State.Type.PAUSED && run.generation().current().isPresent()) {
            execution.completeTaskRunGeneration(taskRunId);
        }
        switch (state) {
            case PAUSED -> execution.pauseTaskRun(taskRunId);
            case SKIPPED -> execution.skipTaskRun(taskRunId);
            default -> applyCompletion(context, requireTask(context, run), run, state, run.outputs(),
                    state == State.Type.FAILED ? "Orchestration task failed: " + run.taskId() : null);
        }
        context.captureState();
    }

    /**
     * 将 Worker 终态及具体输出字段应用到当前 RUNNING 任务并捕获执行状态。
     * @param context 非 null 的当前执行上下文，会被修改
     * @param result 非 null 的 Worker 结果信封，只读
     * @throws IllegalStateException 结果身份或任务状态不匹配时抛出
     * @throws WorkflowException 输出字段与 Task 代码定义不匹配时抛出
     */
    public void applyResult(ExecutorContext context, WorkerTaskResult result) {
        requireExecution(context, result.executionId());
        Execution execution = context.execution();
        TaskRun taskRun = execution.requireTaskRun(result.taskRunId());
        if (!taskRun.state().is(State.Type.RUNNING)) {
            throw new IllegalStateException("Worker result requires a RUNNING TaskRun");
        }
        Task task = context.flow().findTask(taskRun.taskId()).orElseThrow(() -> new WorkflowException("Task definition does not exist: " + taskRun.taskId()));
        applyCompletion(context, task, taskRun, result.targetState(), result.outputs(), result.error());
        context.captureState();
    }

    /**
     * 将独立子运行的终态与具体 Output 应用到精确父调用节点。
     * @param context 父运行的最新上下文，会被修改
     * @param childFlow 子运行绑定的定义
     * @param child 已持久化的子运行终态
     * @throws IllegalArgumentException 子运行来源与父节点不一致时抛出
     */
    public void completeSubFlow(ExecutorContext context, Flow childFlow, Execution child) {
        Execution parent = context.execution();
        if (!child.isTerminal() || !parent.id().equals(child.origin().parentId())
                || !parent.companyId().equals(child.companyId()) || child.parentTaskRunId() == null) {
            throw new IllegalArgumentException("SubFlow completion does not belong to this parent");
        }
        TaskRun caller = parent.requireTaskRun(child.parentTaskRunId());
        Task task = requireTask(context, caller);
        if (!(task instanceof ExecutableTask<?> executable)) {
            throw new IllegalArgumentException("SubFlow caller no longer declares a child flow");
        }
        ExecutableTask.Request request = executable.createExecution(parent.inputs());
        if (!request.flowKey().equals(child.flowKey()) || request.flowVersion() != child.flowVersion()) {
            throw new IllegalArgumentException("SubFlow completion has a different target version");
        }
        if (child.state().is(State.Type.SUCCESS) || child.state().is(State.Type.WARNING)) {
            RunContext childContext = RunContext.builder().variables(RunVariables.builder()
                    .flow(childFlow).execution(child).build()).build();
            Output output = executable.completeExecution(childContext);
            applyCompletion(context, task, caller, child.state().current(),
                    TaskOutputs.values(output), null);
        } else {
            String error = child.taskRuns().stream().flatMap(run -> run.error().stream())
                    .findFirst().orElse("Child execution ended with " + child.state().current());
            applyCompletion(context, task, caller, State.Type.FAILED, Map.of(),
                    "SubFlow " + child.id() + " failed: " + error);
        }
        context.captureState();
    }

    /**
     * 将 Runnable 或编排结果应用到同一任务状态机，成功数据按 Task 的代码定义校验。
     * @param context 非 null 的执行上下文，状态会被修改
     * @param task 非 null 的任务定义，只读
     * @param taskRun 非 null 的当前运行记录
     * @param targetState 非 null 的任务终态
     * @param outputs 非 null 的业务结果映射，只读
     * @param error FAILED 时为非空白原因，其他状态为 null
     * @throws IllegalArgumentException 失败原因与状态不匹配时抛出
     * @throws IllegalStateException 状态不能由任务结果产生时抛出
     */
    private static void applyCompletion(
        ExecutorContext context, Task task, TaskRun taskRun, State.Type targetState,
        Map<String, ?> outputs, String error
    ) {
        if (targetState == State.Type.FAILED ? error == null || error.isBlank() : error != null) {
            throw new IllegalArgumentException("Task error must be present only for FAILED output");
        }
        Execution execution = context.execution();
        switch (targetState) {
            case SUCCESS -> execution.succeedTaskRun(taskRun.id(), task.validateOutputs(outputs));
            case WARNING -> execution.warnTaskRun(taskRun.id(), task.validateOutputs(outputs));
            case FAILED -> execution.failTaskRun(taskRun.id(), error);
            case KILLED -> {
                if (!execution.state().is(State.Type.KILLING)) {
                    execution.beginKilling();
                }
                execution.killUnfinishedTaskRuns();
                execution.finishKilling();
            }
            case CREATED, RUNNING, PAUSED, RESTARTED, SKIPPED, KILLING ->
                    throw new IllegalStateException("Worker cannot return " + targetState);
        }
    }

    public void resume(ExecutorContext context, String taskRunId, Map<String, ?> outputs) {
        context.execution().resumeTaskRun(taskRunId, outputs);
        context.captureState();
    }

    public void kill(ExecutorContext context) {
        context.execution().beginKilling();
        context.captureState();
    }

    /**
     * 只读检查剩余工作并将运行收敛为成功、警告或稳定暂停。
     * @param context 当前运行上下文，收敛时修改聚合
     * @param progressed 本轮已有推进时为 true，此时等待下一周期再判断稳定暂停
     * @throws WorkflowException 没有未完成节点但流程仍不能收敛时抛出
     */
    private static void handleEnd(ExecutorContext context, boolean progressed) {
        Execution execution = context.execution();
        if (!execution.state().is(State.Type.RUNNING)) {
            return;
        }
        if (OrchestrationContext.root(context.flow(), execution).settled(context.flow().tasks(), null)) {
            if (execution.effectiveTaskRuns().stream().anyMatch(taskRun ->
                taskRun.state().is(State.Type.WARNING)
            )) {
                execution.warn();
            } else {
                execution.succeed();
            }
            context.captureState();
            return;
        }
        if (progressed) return;
        if (canPauseExecution(context)) {
            execution.pauseTaskRuns(execution.activeTaskRuns().stream().map(TaskRun::id).toList());
            execution.pause();
            context.captureState();
            return;
        }
        if (execution.unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException("Execution has no runnable Task but the Flow is not settled: " + execution.id());
        }
    }

    /**
     * 检查是否只剩暂停节点及等待它们的编排作用域。
     * @param context 当前运行上下文，只读
     * @return 存在暂停节点且没有可执行活动任务时为 true
     */
    private static boolean canPauseExecution(ExecutorContext context) {
        Execution execution = context.execution();
        if (execution.pausedTaskRuns().isEmpty()) {
            return false;
        }
        return execution.activeTaskRuns().stream().allMatch(taskRun -> {
            Task task = requireTask(context, taskRun);
            return taskRun.state().is(State.Type.RUNNING) && task instanceof OrchestrationTask<?>;
        });
    }

    /**
     * 校验待接纳节点只实现三类执行能力中的一种。
     * @param nexts 待执行节点列表，只读
     * @throws IllegalStateException 节点状态或执行能力不合法时抛出
     */
    private static void requireRuntimeCapabilities(List<ResolvedNextTask> nexts) {
        for (ResolvedNextTask next : nexts) {
            TaskRun taskRun = next.taskRun();
            if (!taskRun.state().is(State.Type.CREATED)) {
                throw new IllegalStateException("Only a CREATED TaskRun can be dispatched: " + taskRun.id());
            }
            Task task = next.task();
            boolean runnable = task instanceof RunnableTask;
            boolean orchestration = task instanceof OrchestrationTask;
            if ((runnable ? 1 : 0) + (orchestration ? 1 : 0) + (task instanceof ExecutableTask ? 1 : 0) != 1) {
                throw new IllegalStateException("Task must implement exactly one runtime capability: " + task.getType());
            }
        }
    }

    private static Task requireTask(ExecutorContext context, TaskRun taskRun) {
        return context.flow().findTask(taskRun.taskId()).orElseThrow(() -> new IllegalStateException("TaskRun references a missing Task: " + taskRun.taskId()));
    }

    private static void requireExecution(ExecutorContext context, String executionId) {
        if (!context.execution().identifiedBy(executionId)) {
            throw new IllegalArgumentException("Worker message belongs to another Execution");
        }
    }

}
