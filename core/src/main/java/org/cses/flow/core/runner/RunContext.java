package org.cses.flow.core.runner;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable invocation context for one {@link RunnableTask}.
 *
 * <p>The variables map carries invocation-scoped runtime values. The Executor
 * places the current {@link Execution}, exact TaskRun identity and actual
 * TaskRun inputs in reserved entries before the context crosses into a
 * RunnableTask.</p>
 */
public final class RunContext {

    /** Reserved variable containing the current Flow execution aggregate. */
    public static final String EXECUTION_VARIABLE = "$flow.execution";

    /** Reserved variable containing the exact current TaskRun id. */
    public static final String TASK_RUN_ID_VARIABLE = "$flow.taskRunId";

    /** Reserved variable containing the current TaskRun's optional parent. */
    public static final String PARENT_TASK_RUN_ID_VARIABLE =
        "$flow.parentTaskRunId";

    /** Reserved variable containing the actual inputs for this TaskRun. */
    public static final String INPUTS_VARIABLE = "$flow.inputs";

    private final Session<? extends User> session;
    private final DSLContext dsl;
    private final Map<String, Object> variables;

    private RunContext(
        Session<? extends User> session,
        DSLContext dsl,
        Map<String, ?> variables
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.variables = immutableVariables(variables);
    }

    public static RunContext create(
        Session<? extends User> session,
        DSLContext dsl,
        Map<String, ?> variables
    ) {
        return new RunContext(session, dsl, variables);
    }

    public Session<? extends User> session() {
        return session;
    }

    public DSLContext dsl() {
        return dsl;
    }

    /**
     * Returns the immutable invocation variables.
     *
     * <p>The map itself is immutable. Values such as {@link Execution} are
     * runtime objects and must not be used by a Task to mutate Flow state.</p>
     */
    public Map<String, Object> variables() {
        return variables;
    }

    /**
     * Returns the Flow execution that owns this RunnableTask invocation.
     */
    public String executionId() {
        Object value = variables.get(EXECUTION_VARIABLE);
        if (!(value instanceof Execution execution)) {
            throw new IllegalStateException(
                "RunContext variables must contain an Execution under "
                    + EXECUTION_VARIABLE
            );
        }
        return execution.id();
    }

    /**
     * Returns the exact TaskRun id for this RunnableTask invocation.
     */
    public String taskRunId() {
        Object value = variables.get(TASK_RUN_ID_VARIABLE);
        if (!(value instanceof String taskRunId) || taskRunId.isBlank()) {
            throw new IllegalStateException(
                "RunContext variables must contain a TaskRun id under "
                    + TASK_RUN_ID_VARIABLE
            );
        }
        return taskRunId;
    }

    /**
     * Returns the immediate parent TaskRun id for a nested invocation.
     * Top-level RunnableTasks have no parent.
     */
    public Optional<String> parentTaskRunId() {
        Object value = variables.get(PARENT_TASK_RUN_ID_VARIABLE);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String parentTaskRunId)
            || parentTaskRunId.isBlank()) {
            throw new IllegalStateException(
                "RunContext variable " + PARENT_TASK_RUN_ID_VARIABLE
                    + " must contain a non-blank TaskRun id"
            );
        }
        return Optional.of(parentTaskRunId);
    }

    public Map<String, Object> inputs() {
        Object value = variables.get(INPUTS_VARIABLE);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException(
                "RunContext variable " + INPUTS_VARIABLE
                    + " must contain a Map"
            );
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) value;
        return inputs;
    }

    /**
     * Renders a Task definition template against this invocation's inputs.
     */
    public String render(TemplateExpression expression) {
        return Objects.requireNonNull(expression, "expression")
            .render(inputs());
    }

    private static Map<String, Object> immutableVariables(
        Map<String, ?> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = Objects.requireNonNull(
                key,
                "RunContext variable key"
            );
            Object normalizedValue = Objects.requireNonNull(
                value,
                () -> "RunContext variable must not be null: " + key
            );
            if (INPUTS_VARIABLE.equals(normalizedKey)) {
                copy.put(normalizedKey, immutableInputs(normalizedValue));
            } else {
                copy.put(normalizedKey, normalizedValue);
            }
        });
        return Map.copyOf(copy);
    }

    private static Map<String, Object> immutableInputs(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(
                "RunContext variable " + INPUTS_VARIABLE
                    + " must contain a Map"
            );
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, input) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException(
                    "RunContext input keys must be strings"
                );
            }
            copy.put(
                stringKey,
                Objects.requireNonNull(
                    input,
                    () -> "RunContext input must not be null: " + stringKey
                )
            );
        });
        return Map.copyOf(copy);
    }
}
