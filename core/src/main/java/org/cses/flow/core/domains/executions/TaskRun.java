package org.cses.flow.core.domains.executions;

import org.cses.flow.core.domains.Identified;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.utils.RequiredUtil;
import org.paas.common.util.StringUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One Task execution occurrence, transient until accepted by Execution.
 */
public class TaskRun implements Identified {

    private String id;
    private String taskId;
    private String parentId;
    private Integer iteration;
    private Integer executionGenerationVersion;
    private Map<String, Object> inputs;
    private Generation generation;
    private State state;
    private Map<String, Object> outputs;
    private String error;
    private java.util.List<TaskRunAttempt> attempts = java.util.List.of();
    private Map<String, Object> retryVariables = Map.of();
    private Long nextRetryAt;

    /** @return 不可变调用历史 */
    public java.util.List<TaskRunAttempt> attempts() { return attempts; }
    /** @return 已开始调用的次数，尚未调用为 0 */
    public int attemptNumber() { return attempts.size(); }
    /** @return 首次调用变量快照，未调用为空 */
    public Map<String, Object> retryVariables() { return retryVariables; }
    /** @return 待调度毫秒时间，没有计划为 null */
    public Long nextRetryAt() { return nextRetryAt; }

    /**
     * 保存一次实际 Worker 调用及首次变量快照。
     * @param variables 非空调用变量
     * @param now 开始毫秒时间
     */
    void beginAttempt(Map<String, ?> variables, long now) {
        requireState(State.Type.RUNNING);
        if (!attempts.isEmpty() && attempts.getLast().completedAt() == null) throw new IllegalStateException("Attempt already running");
        if (attempts.isEmpty()) retryVariables = immutableMap(variables);
        var changed = new java.util.ArrayList<>(attempts);
        changed.add(TaskRunAttempt.rehydrate(changed.size() + 1, now, null, State.Type.RUNNING, null));
        attempts = java.util.List.copyOf(changed);
        nextRetryAt = null;
    }

    /**
     * 完成本次调用，保留所有较早结果。
     * @param target Worker 终态
     * @param failure 失败时必填的原因
     * @param now 完成毫秒时间
     */
    void finishAttempt(State.Type target, String failure, long now) {
        requireState(State.Type.RUNNING);
        if (attempts.isEmpty() || attempts.getLast().completedAt() != null) throw new IllegalStateException("No running attempt");
        var changed = new java.util.ArrayList<>(attempts);
        var previous = changed.removeLast();
        changed.add(TaskRunAttempt.rehydrate(previous.number(), previous.startedAt(), now, target, failure));
        attempts = java.util.List.copyOf(changed);
    }

    /**
     * 将本次失败保留为历史并进入等待，根继续运行。
     * @param nextAt 非负计划毫秒时间
     */
    void waitForRetry(long nextAt) {
        requireState(State.Type.RUNNING);
        if (attempts.isEmpty() || attempts.getLast().state() != State.Type.FAILED
            || nextAt < attempts.getLast().completedAt()) throw new IllegalArgumentException("Invalid retry plan");
        outputs = Map.of();
        error = null;
        nextRetryAt = nextAt;
        state = state.withState(State.Type.RETRY_WAIT);
    }

    /**
     * 将到期等待重新放入普通调度入口，保留到期事实直到实际领取。
     * @param now 当前毫秒时间
     * @return 是否发生状态变化
     */
    boolean retry(long now) {
        if (!state.is(State.Type.RETRY_WAIT) || nextRetryAt == null || nextRetryAt > now) return false;
        state = state.withState(State.Type.CREATED);
        return true;
    }

    /**
     * 从数据库恢复调用历史和待调度事实。
     * @param id 稳定身份
     * @param taskId 定义身份
     * @param parentId 可空父身份
     * @param iteration 可空循环轮次
     * @param version 可空执行片段版本
     * @param inputs 已绑定输入
     * @param generation 轮次历史
     * @param state 完整状态
     * @param outputs 当前输出
     * @param error 仅最终失败时可用
     * @param attempts 非空引用的尝试历史
     * @param variables 首次调用快照
     * @param nextRetryAt 可空待调度时间
     * @return 恢复的独立任务记录
     */
    public static TaskRun rehydrate(String id, String taskId, String parentId, Integer iteration,
            Integer version, Map<String, ?> inputs, Generation generation, State state,
            Map<String, ?> outputs, String error, java.util.List<TaskRunAttempt> attempts,
            Map<String, ?> variables, Long nextRetryAt) {
        TaskRun run = rehydrate(id, taskId, parentId, iteration, version, inputs, generation, state, outputs, error);
        run.attempts = java.util.List.copyOf(attempts);
        run.retryVariables = immutableMap(variables);
        run.nextRetryAt = nextRetryAt;
        for (int index = 0; index < attempts.size(); index++) {
            var attempt = attempts.get(index);
            if (attempt.number() != index + 1 || index < attempts.size() - 1 && attempt.completedAt() == null) {
                throw new IllegalArgumentException("Invalid attempt sequence");
            }
        }
        if (nextRetryAt != null && (nextRetryAt < 0
            || !(state.is(State.Type.RETRY_WAIT) || state.is(State.Type.CREATED))
            || attempts.isEmpty() || attempts.getLast().state() != State.Type.FAILED
            || nextRetryAt < attempts.getLast().completedAt())
            || state.is(State.Type.RETRY_WAIT) && nextRetryAt == null) {
            throw new IllegalArgumentException("Invalid persisted retry plan");
        }
        if (!attempts.isEmpty() && (run.retryVariables.isEmpty()
            || attempts.getLast().completedAt() == null && !state.is(State.Type.RUNNING))) {
            throw new IllegalArgumentException("Invalid persisted attempt context");
        }
        return run;
    }

    private TaskRun(
        String id,
        String taskId,
        String parentId,
        Integer iteration,
        Integer executionGenerationVersion,
        Map<String, ?> inputs
    ) {
        this.id = requireText(id, "TaskRun id");
        this.taskId = requireText(taskId, "Task id");
        this.parentId = normalizeOptionalText(parentId);
        this.iteration = normalizeIteration(iteration, this.parentId);
        this.executionGenerationVersion = normalizeGenerationVersion(
            executionGenerationVersion
        );
        this.inputs = immutableMap(inputs);
        this.generation = Generation.empty();
        this.state = State.created();
        this.outputs = Map.of();
    }

    /**
     * Creates a transient TaskRun that may be staged by one executor cycle.
     *
     * <p>The TaskRun does not belong to an Execution until the aggregate
     * accepts it through
     * {@link Execution#startWithTaskRuns(java.util.List)} or
     * {@link Execution#addTaskRuns(java.util.List)}.</p>
     */
    public static TaskRun create(
        String taskId,
        String parentTaskRunId,
        Map<String, ?> inputs
    ) {
        return create(taskId, parentTaskRunId, inputs, null);
    }

    /**
     * Creates one direct loop-body TaskRun for the supplied iteration.
     */
    public static TaskRun create(
        String taskId,
        String parentTaskRunId,
        Map<String, ?> inputs,
        Integer iteration
    ) {
        return create(
            taskId,
            parentTaskRunId,
            inputs,
            iteration,
            null
        );
    }

    public static TaskRun create(
        String taskId,
        String parentTaskRunId,
        Map<String, ?> inputs,
        Integer iteration,
        Integer executionGenerationVersion
    ) {
        return new TaskRun(
            StringUtil.newId(),
            taskId,
            parentTaskRunId,
            iteration,
            executionGenerationVersion,
            inputs
        );
    }

    private TaskRun(
        String id,
        String taskId,
        String parentId,
        Integer iteration,
        Integer executionGenerationVersion,
        Map<String, ?> inputs,
        Generation generation,
        State state,
        Map<String, ?> outputs,
        String error
    ) {
        this.id = requireText(id, "TaskRun id");
        this.taskId = requireText(taskId, "Task id");
        this.parentId = normalizeOptionalText(parentId);
        this.iteration = normalizeIteration(iteration, this.parentId);
        this.executionGenerationVersion = normalizeGenerationVersion(
            executionGenerationVersion
        );
        this.inputs = immutableMap(inputs);
        this.generation = RequiredUtil.required(
            generation,
            "TaskRun generation"
        ).copy();
        this.state = RequiredUtil.required(state, "TaskRun state");
        this.outputs = immutableMap(outputs);
        this.error = normalizeOptionalText(error);
        if (!state.is(State.Type.FAILED) && this.error != null) {
            throw new IllegalArgumentException(
                "Only a failed TaskRun may have an error"
            );
        }
        validateStateRoute(state);
    }

    /**
     * Rehydrates a TaskRun from a trusted persistence adapter.
     */
    public static TaskRun rehydrate(
        String id,
        String taskId,
        String parentTaskRunId,
        Integer iteration,
        Map<String, ?> inputs,
        State state,
        Map<String, ?> outputs,
        String error
    ) {
        return rehydrate(
            id,
            taskId,
            parentTaskRunId,
            iteration,
            null,
            inputs,
            Generation.empty(),
            state,
            outputs,
            error
        );
    }

    public static TaskRun rehydrate(
        String id,
        String taskId,
        String parentTaskRunId,
        Integer iteration,
        Integer executionGenerationVersion,
        Map<String, ?> inputs,
        Generation generation,
        State state,
        Map<String, ?> outputs,
        String error
    ) {
        return new TaskRun(
            id,
            taskId,
            parentTaskRunId,
            iteration,
            executionGenerationVersion,
            inputs,
            generation,
            state,
            outputs,
            error
        );
    }

    public String id() {
        return id;
    }

    public String taskId() {
        return taskId;
    }

    public Optional<String> parentId() {
        return Optional.ofNullable(parentId);
    }

    /**
     * Returns the one-based iteration for a direct loop-body occurrence.
     */
    public OptionalInt iteration() {
        return iteration == null
            ? OptionalInt.empty()
            : OptionalInt.of(iteration);
    }

    public OptionalInt executionGenerationVersion() {
        return executionGenerationVersion == null
            ? OptionalInt.empty()
            : OptionalInt.of(executionGenerationVersion);
    }

    public Generation generation() {
        return generation.copy();
    }

    public Map<String, Object> inputs() {
        return inputs;
    }

    public State state() {
        return state;
    }

    public Map<String, Object> outputs() {
        return outputs;
    }

    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    public boolean isActive() {
        return state.isActive();
    }

    public boolean isPaused() {
        return state.isPaused();
    }

    public boolean isUnfinished() {
        return state.is(State.Type.CREATED)
            || state.is(State.Type.RUNNING)
            || state.is(State.Type.PAUSED)
            || state.is(State.Type.RETRY_WAIT);
    }

    void start() {
        requireState(State.Type.CREATED);
        state = state.running();
    }

    /**
     * 记录已绑定的调用输入后启动本次运行。
     * @param boundInputs 非 null 的已校验参数，复制保存
     * @throws org.cses.flow.core.exceptions.WorkflowException 当前不是 CREATED 时抛出
     */
    void start(Map<String, ?> boundInputs) {
        requireState(State.Type.CREATED);
        inputs = immutableMap(boundInputs);
        state = state.running();
    }

    void pause() {
        requireState(State.Type.RUNNING);
        state = state.paused();
    }

    void succeed(Map<String, ?> completedOutputs) {
        requireState(State.Type.RUNNING);
        outputs = immutableMap(completedOutputs);
        error = null;
        state = state.success();
    }

    void skip() {
        requireState(State.Type.RUNNING);
        outputs = Map.of();
        error = null;
        state = state.skipped();
    }

    void warn(Map<String, ?> warningOutputs) {
        requireState(State.Type.RUNNING);
        outputs = immutableMap(warningOutputs);
        error = null;
        state = state.warning();
    }

    void resume(Map<String, ?> completedOutputs) {
        requireState(State.Type.PAUSED);
        outputs = immutableMap(completedOutputs);
        error = null;
        state = state.running();
    }

    /**
     * Clears previous outputs and appends RUNNING when a partially affected ancestor must resume.
     *
     * @throws WorkflowException when the ancestor is neither running, paused nor successfully completed
     */
    void reopenForRewind() {
        if (state.is(State.Type.RUNNING)) return;
        if (!state.is(State.Type.PAUSED) && !state.is(State.Type.SUCCESS) && !state.is(State.Type.WARNING)) {
            throw new WorkflowException("Rewind ancestor cannot reopen: " + id);
        }
        outputs = Map.of();
        error = null;
        state = state.running();
    }

    /**
     * 保存最终失败原因并清除重试计划。
     * @param failure 非空白错误原因
     * @throws WorkflowException 当前不是运行或待重试状态时抛出
     */
    void fail(String failure) {
        if (!(state.is(State.Type.RUNNING) || nextRetryAt != null
            && (state.is(State.Type.CREATED) || state.is(State.Type.RETRY_WAIT)))) {
            throw new WorkflowException("TaskRun cannot fail from " + state);
        }
        if (failure == null || failure.isBlank()) {
            throw new IllegalArgumentException("TaskRun error must not be blank");
        }
        nextRetryAt = null;
        error = failure.trim();
        state = state.failed();
    }

    /**
     * 关闭未完成节点和当前调用，清除尚未确认完成的输出及待重试计划。
     * @throws WorkflowException 当前节点已经结束时抛出
     */
    void kill() {
        if (!isUnfinished()) {
            throw new WorkflowException(
                "TaskRun cannot be killed from " + state + ": " + id
            );
        }
        State killed = state.killed();
        if (!attempts.isEmpty() && attempts.getLast().completedAt() == null) {
            finishAttempt(State.Type.KILLED, null, killed.history().getLast().date());
        }
        nextRetryAt = null;
        outputs = Map.of();
        error = null;
        state = killed;
    }

    void startGeneration(String reason) {
        generation.start(reason);
    }

    void advanceGeneration(String reason) {
        generation.advance(reason);
    }

    void completeGeneration() {
        generation.complete();
    }

    void completeGenerationIfActive() {
        if (generation.active()) {
            generation.complete();
        }
    }

    /** @return 保留身份、完整尝试历史与重试计划的独立节点副本 */
    TaskRun copy() {
        return rehydrate(
            id,
            taskId,
            parentId,
            iteration,
            executionGenerationVersion,
            inputs,
            generation,
            state,
            outputs,
            error,
            attempts, retryVariables, nextRetryAt
        );
    }

    private void requireState(State.Type expected) {
        if (!state.is(expected)) {
            throw new WorkflowException(
                "TaskRun " + id + " must be " + expected + " but was " + state
            );
        }
    }

    /**
     * Checks persisted TaskRun transitions, including successful ancestors reopened by rewind.
     *
     * @param state complete persisted state history to read
     * @throws IllegalArgumentException when a recorded transition is invalid
     */
    private static void validateStateRoute(State state) {
        java.util.List<State.History> history = state.history();
        for (int index = 1; index < history.size(); index++) {
            State.Type source = history.get(index - 1).state();
            State.Type target = history.get(index).state();
            boolean valid = switch (source) {
                case CREATED ->
                    target == State.Type.RUNNING
                        || target == State.Type.FAILED
                        || target == State.Type.KILLED;
                case RUNNING ->
                    target == State.Type.RETRY_WAIT
                        || target == State.Type.PAUSED
                        || target == State.Type.SUCCESS
                        || target == State.Type.SKIPPED
                        || target == State.Type.WARNING
                        || target == State.Type.FAILED
                        || target == State.Type.KILLED;
                case RETRY_WAIT -> target == State.Type.CREATED || target == State.Type.FAILED || target == State.Type.KILLED;
                case PAUSED ->
                    target == State.Type.RUNNING
                        || target == State.Type.KILLED;
                case SUCCESS, WARNING -> target == State.Type.RUNNING;
                case RESTARTED, SKIPPED, FAILED, KILLING, KILLED -> false;
            };
            if (!valid) {
                throw new IllegalArgumentException(
                    "Invalid TaskRun state transition from "
                        + source + " to " + target
                );
            }
        }
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
            key,
            immutableValue(value)
        ));
        return Collections.unmodifiableMap(copy);
    }

    private static String requireText(String value, String field) {
        return RequiredUtil.required(value, field + " must not be blank")
            .trim();
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Integer normalizeIteration(
        Integer value,
        String parentTaskRunId
    ) {
        if (value == null) {
            return null;
        }
        if (value < 1) {
            throw new IllegalArgumentException(
                "TaskRun iteration must be positive"
            );
        }
        if (parentTaskRunId == null) {
            throw new IllegalArgumentException(
                "An iterated TaskRun must have a parent"
            );
        }
        return value;
    }

    private static Integer normalizeGenerationVersion(Integer value) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(
                "TaskRun execution Generation version must be positive"
            );
        }
        return value;
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(
                String.valueOf(key),
                immutableValue(nested)
            ));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(TaskRun::immutableValue).toList();
        }
        return value;
    }
}
