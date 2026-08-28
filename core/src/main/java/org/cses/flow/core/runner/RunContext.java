package org.cses.flow.core.runner;

import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.expressions.VariablePath;
import org.cses.flow.core.domains.tasks.RunnableTask;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable invocation context for one {@link RunnableTask}.
 *
 * <p>All invocation facts are read from the canonical variable tree built by
 * {@link RunVariables}. The context does not duplicate runtime identity or
 * retain a Session.</p>
 */
public class RunContext {

    private Map<String, Object> variables;

    @lombok.Builder(builderClassName = "Builder")
    private RunContext(Map<String, ?> variables) {
        this.variables = RunVariables.immutableCopy(
                Objects.requireNonNull(variables, "Run variables")
        );
    }

    public Map<String, Object> variables() {
        return variables;
    }

    public Optional<String> parentTaskRunId() {
        return VariablePath.parse("parent.taskRun.id")
                .resolve(variables)
                .map(value -> requiredString("parent.taskRun.id", value));
    }

    public Map<String, Object> inputs() {
        return requiredMap("inputs");
    }

    public Map<String, Object> taskInputs() {
        return requiredMap("taskRun.inputs");
    }

    public Map<String, Object> flowVariables() {
        return requiredMap("vars");
    }

    public String render(TemplateExpression expression) {
        return Objects.requireNonNull(expression, "expression")
                .render(variables);
    }

    private String requiredString(String path) {
        Object value = VariablePath.parse(path).resolve(variables)
                .orElseThrow(() -> new IllegalStateException(
                        "RunContext variable path is missing: " + path
                ));
        return requiredString(path, value);
    }

    private String requiredString(String path, Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException(
                    "RunContext variable path must contain text: " + path
            );
        }
        return text;
    }

    private Map<String, Object> requiredMap(String path) {
        Object value = VariablePath.parse(path).resolve(variables)
                .orElseThrow(() -> new IllegalStateException(
                        "RunContext variable path is missing: " + path
                ));
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "RunContext variable path must contain a Map: " + path
            );
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) map;
        return values;
    }

    public TaskRunInfo taskRunInfo() {
        return TaskRunInfo.from(
                requiredString("execution.id"),
                requiredString("taskRun.id"),
                requiredString("task.id"),
                requiredString("task.key"),
                requiredMap("taskRun.outputs")
        );
    }

    @SuppressWarnings("unchecked")
    public FlowInfo flowInfo() {
        Object value = variables.get("flow");
        if (value == null) {
            return FlowInfo.empty();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "RunContext variable path must contain a Map: flow"
            );
        }
        return FlowInfo.from((Map<String, Object>) map);
    }

    public record TaskRunInfo(
            String executionId,
            String id,
            String taskId,
            String taskKey,
            Map<String, Object> outputs
    ) {

        public TaskRunInfo {
            executionId = requireInfoText(executionId, "Execution id");
            id = requireInfoText(id, "TaskRun id");
            taskId = requireInfoText(taskId, "Task id");
            taskKey = requireInfoText(taskKey, "Task key");
            outputs = RunVariables.immutableCopy(
                    Objects.requireNonNull(outputs, "TaskRun outputs")
            );
        }

        public static TaskRunInfo from(
                String executionId,
                String id,
                String taskId,
                String taskKey,
                Map<String, Object> outputs
        ) {
            return new TaskRunInfo(
                    executionId,
                    id,
                    taskId,
                    taskKey,
                    outputs
            );
        }

        private static String requireInfoText(
                String value,
                String field
        ) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        field + " must not be blank"
                );
            }
            return value.trim();
        }
    }

    public record FlowInfo(
            String id,
            String key,
            String companyId,
            Long version
    ) {

        public static FlowInfo from(Map<String, Object> flowInfoMap) {
            Objects.requireNonNull(flowInfoMap, "Flow info");
            return new FlowInfo(
                    optionalString(flowInfoMap, "id"),
                    optionalString(flowInfoMap, "key"),
                    optionalString(flowInfoMap, "companyId"),
                    optionalLong(flowInfoMap, "version")
            );
        }

        public static FlowInfo empty() {
            return new FlowInfo(null, null, null, null);
        }

        private static String optionalString(
                Map<String, Object> source,
                String key
        ) {
            Object value = source.get(key);
            if (value == null || value instanceof String) {
                return (String) value;
            }
            throw new IllegalStateException(
                    "RunContext flow variable must contain text: " + key
            );
        }

        private static Long optionalLong(
                Map<String, Object> source,
                String key
        ) {
            Object value = source.get(key);
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.longValue();
            }
            throw new IllegalStateException(
                    "RunContext flow variable must contain a Long: " + key
            );
        }
    }
}
