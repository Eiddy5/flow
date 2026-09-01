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
public final class TaskRun implements Identified {

    private final String id;
    private final String taskId;
    private final String parentId;
    private final Integer iteration;
    private final Integer executionGenerationVersion;
    private final Map<String, Object> inputs;
    private Generation generation;
    private State state;
    private Map<String, Object> outputs;
    private String error;

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
            || state.is(State.Type.PAUSED);
    }

    void start() {
        requireState(State.Type.CREATED);
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

    void fail(String failure) {
        requireState(State.Type.RUNNING);
        if (failure == null || failure.isBlank()) {
            throw new IllegalArgumentException("TaskRun error must not be blank");
        }
        error = failure.trim();
        state = state.failed();
    }

    void kill() {
        if (!isUnfinished()) {
            throw new WorkflowException(
                "TaskRun cannot be killed from " + state + ": " + id
            );
        }
        error = null;
        state = state.killed();
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
            error
        );
    }

    private void requireState(State.Type expected) {
        if (!state.is(expected)) {
            throw new WorkflowException(
                "TaskRun " + id + " must be " + expected + " but was " + state
            );
        }
    }

    private static void validateStateRoute(State state) {
        java.util.List<State.History> history = state.history();
        for (int index = 1; index < history.size(); index++) {
            State.Type source = history.get(index - 1).state();
            State.Type target = history.get(index).state();
            boolean valid = switch (source) {
                case CREATED ->
                    target == State.Type.RUNNING
                        || target == State.Type.KILLED;
                case RUNNING ->
                    target == State.Type.PAUSED
                        || target == State.Type.SUCCESS
                        || target == State.Type.SKIPPED
                        || target == State.Type.WARNING
                        || target == State.Type.FAILED
                        || target == State.Type.KILLED;
                case PAUSED ->
                    target == State.Type.RUNNING
                        || target == State.Type.KILLED;
                case RESTARTED, SUCCESS, SKIPPED, WARNING, FAILED, KILLING,
                    KILLED -> false;
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
