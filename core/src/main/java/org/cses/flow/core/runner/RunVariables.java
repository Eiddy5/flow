package org.cses.flow.core.runner;

import com.google.common.collect.ImmutableMap;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Task;

import java.util.*;

/**
 * Builds the immutable variable tree used by runtime expressions.
 */
public class RunVariables {

    private RunVariables() {
    }

    public static Builder builder() {
        return new Builder();
    }

    static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "Run variable key"),
                immutableValue(value)
        ));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> copy = new LinkedHashMap<>();
            nested.forEach((key, nestedValue) -> copy.put(
                    String.valueOf(key),
                    immutableValue(nestedValue)
            ));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(RunVariables::immutableValue)
                    .toList();
        }
        return value;
    }

    static Map<String, Object> of(final Flow flow) {
        ImmutableMap.Builder<String, Object> builder =
                ImmutableMap.builder();
        builder.put("id", flow.id())
                .put("key", flow.key());
        Optional.ofNullable(flow.versionOrNull())
                .ifPresent(version -> builder.put("version", version));
        Optional.ofNullable(flow.companyId())
                .ifPresent(companyId -> builder.put("companyId", companyId));
        return builder.build();
    }

    static Map<String, Object> of(final Task task) {
        ImmutableMap.Builder<String, Object> builder =
                ImmutableMap.builder();
        builder.put("id", task.id())
                .put("key", task.key())
                .put("type", task.getType());
        return builder.build();
    }

    static Map<String, Object> of(final TaskRun taskRun) {
        ImmutableMap.Builder<String, Object> builder =
                ImmutableMap.builder();
        builder.put("id", taskRun.id())
                .put("state", taskRun.state().current().name())
                .put("inputs", taskRun.inputs())
                .put("outputs", taskRun.outputs());

        taskRun.iteration().ifPresent(
                iteration -> builder.put("iteration", iteration)
        );

        return builder.build();
    }

    static Map<String, Object> of(final Execution execution) {
        ImmutableMap.Builder<String, Object> builder =
                ImmutableMap.builder();
        builder.put("id", execution.id())
                .put("flowKey", execution.flowKey())
                .put("flowVersion", execution.flowVersion())
                .put("state", execution.state().current().name())
                .put("outputs", Map.of());
        return builder.build();
    }

    /**
     * Collects domain facts and projects them into expression values.
     *
     * <p>The builder deliberately accepts no projected maps or standalone
     * identifiers. Every exposed value is resolved from these four domain
     * roots.</p>
     */
    public static class Builder {

        private Flow flow;
        private Execution execution;
        private Task task;
        private TaskRun taskRun;

        public Builder flow(Flow flow) {
            this.flow = flow;
            return this;
        }

        public Builder execution(Execution execution) {
            this.execution = execution;
            return this;
        }

        public Builder task(Task task) {
            this.task = task;
            return this;
        }

        public Builder taskRun(TaskRun taskRun) {
            this.taskRun = taskRun;
            return this;
        }

        public Map<String, Object> build() {
            validateFacts();

            ImmutableMap.Builder<String, Object> builder =
                    ImmutableMap.builder();
            if (flow != null) {
                builder.put("flow", RunVariables.of(flow));
            }

            if (task != null) {
                builder.put("task", RunVariables.of(task));
            }

            if (taskRun != null) {
                builder.put("taskRun", RunVariables.of(taskRun));
            }

            if (execution != null) {
                if (taskRun != null) {
                    List<Map<String, Object>> parents = parentChain();
                    builder.put("parents", parents);
                    if (!parents.isEmpty()) {
                        builder.put("parent", parents.getFirst());
                    }
                }

                builder.put("execution", RunVariables.of(execution));
                builder.put("inputs", execution.inputs());
                builder.put("outputs", completedTaskOutputs());
            }

            if (flow != null) {
                builder.put("vars", flow.variables());
            }

            return immutableCopy(builder.build());
        }

        private void validateFacts() {
            if (flow != null && execution != null
                    && (!flow.key().equals(execution.flowKey())
                    || flow.reversion() != execution.flowVersion())) {
                throw new IllegalArgumentException(
                        "Execution does not belong to the exact Flow reversion"
                );
            }
            if (flow != null && task != null
                    && flow.findTask(task.id()).isEmpty()) {
                throw new IllegalArgumentException(
                        "Task does not belong to the Flow: " + task.key()
                );
            }
            if (task != null && taskRun != null
                    && !task.identifiedBy(taskRun.taskId())) {
                throw new IllegalArgumentException(
                        "TaskRun does not belong to the current Task: "
                                + taskRun.id()
                );
            }
            if (execution != null && taskRun != null
                    && execution.findTaskRun(taskRun.id()).isEmpty()) {
                throw new IllegalArgumentException(
                        "TaskRun does not belong to the Execution: "
                                + taskRun.id()
                );
            }
        }

        private Map<String, Object> completedTaskOutputs() {
            if (flow == null || execution == null) {
                return Map.of();
            }
            Map<String, Object> completed = new LinkedHashMap<>();
            for (TaskRun completedRun : execution.effectiveTaskRuns()) {
                if (!completedRun.state().is(State.Type.SUCCESS)
                        && !completedRun.state().is(State.Type.WARNING)) {
                    continue;
                }
                Task completedTask = flow.findTask(completedRun.taskId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "TaskRun references a missing Flow Task: "
                                        + completedRun.taskId()
                        ));
                completed.put(completedTask.key(), completedRun.outputs());
            }
            return completed;
        }

        private List<Map<String, Object>> parentChain() {
            if (taskRun == null || taskRun.parentId().isEmpty()) {
                return List.of();
            }
            if (execution == null || flow == null) {
                throw new IllegalArgumentException(
                        "Parent variables require Flow and Execution"
                );
            }

            String nextParentId = taskRun.parentId().orElseThrow();
            List<Map<String, Object>> parents = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            while (nextParentId != null) {
                String currentParentId = nextParentId;
                if (!visited.add(currentParentId)) {
                    throw new IllegalArgumentException(
                            "TaskRun parent chain contains a cycle: "
                                    + currentParentId
                    );
                }
                TaskRun parentRun = execution.findTaskRun(currentParentId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Parent TaskRun does not exist: " + currentParentId
                        ));
                Task parentTask = flow.findTask(parentRun.taskId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Parent TaskRun references a missing Flow Task: "
                                        + parentRun.taskId()
                        ));
                parents.add(Map.of(
                        "task", RunVariables.of(parentTask),
                        "taskRun", RunVariables.of(parentRun)
                ));
                nextParentId = parentRun.parentId().orElse(null);
            }
            return List.copyOf(parents);
        }
    }
}
