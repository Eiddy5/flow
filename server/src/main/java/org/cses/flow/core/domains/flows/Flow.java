package org.cses.flow.core.domains.flows;

import lombok.Getter;
import org.cses.flow.core.domains.Deletable;
import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TaskTypeDispatcher;
import org.cses.flow.core.exceptions.WorkflowException;
import org.paas.common.util.StringUtil;
import org.paas.json.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One complete, deployed Flow reversion.
 */
public final class Flow implements Deletable<Flow> {

    private static final Set<String> FLOW_DEFINITION_FIELDS = Set.of(
        "key",
        "description",
        "inputs",
        "outputs",
        "tasks"
    );
    private static final Set<String> TASK_DEFINITION_FIELDS = Set.of(
        "key",
        "type",
        "inputs",
        "outputs",
        "route",
        "dependOn",
        "tasks"
    );
    private static final Set<String> TASK_SYSTEM_FIELDS = Set.of(
        "id",
        "parentId",
        "taskId"
    );
    private static final Set<String> OUTPUT_DEFINITION_FIELDS = Set.of(
        "key",
        "type"
    );

    private final String id;
    private final String companyId;
    private final String key;
    private final long reversion;
    private final String description;
    private final List<Input<?>> inputs;
    private final List<Output> outputs;
    private final List<Task> tasks;
    private final ActorRef creator;
    private final long createdAt;
    @Getter
    private boolean deleted;
    private ActorRef updater;
    private ActorRef deleter;
    private long updatedAt;
    private Long deletedAt;

    private Flow(
        String id,
        String companyId,
        String key,
        long reversion,
        String description,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        List<? extends Task> tasks,
        boolean deleted,
        ActorRef creator,
        ActorRef updater,
        ActorRef deleter,
        long createdAt,
        long updatedAt,
        Long deletedAt
    ) {
        this.id = requireText(id, "Flow id");
        this.companyId = requireText(companyId, "Company id");
        this.key = requireText(key, "Flow key");
        if (reversion < 1) {
            throw new IllegalArgumentException(
                "Flow reversion must be positive"
            );
        }
        this.reversion = reversion;
        this.description = description == null ? "" : description;
        this.inputs = immutableData(inputs, "Flow inputs");
        this.outputs = immutableData(outputs, "Flow outputs");
        this.tasks = tasks == null ? List.of() : List.copyOf(tasks);
        validateDefinition(this.tasks);
        this.creator = Objects.requireNonNull(creator, "Flow creator");
        this.updater = Objects.requireNonNull(updater, "Flow updater");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deleted = deleted;
        this.deleter = deleter;
        this.deletedAt = deletedAt;
        if ((deleter == null) != (deletedAt == null)) {
            throw new IllegalArgumentException(
                "Flow deleter and deletedAt must both be empty or present"
            );
        }
        if (!deleted && deleter != null) {
            throw new IllegalArgumentException(
                "Undeleted Flow must not have deletion audit"
            );
        }
        if (deleted && deleter == null) {
            throw new IllegalArgumentException(
                "Deleted Flow requires deletion audit"
            );
        }
    }

    /**
     * Materializes one complete Flow reversion from a parsed definition.
     */
    public static Flow deploy(
        String companyId,
        String id,
        Map<String, ?> definition,
        Flow latest,
        TaskTypeDispatcher taskTypeDispatcher,
        ActorRef actor,
        long deployedAt
    ) {
        Objects.requireNonNull(
            taskTypeDispatcher,
            "Task type dispatcher"
        );
        Objects.requireNonNull(actor, "Flow creator");
        Objects.requireNonNull(deployedAt, "Flow deployedAt");
        String normalizedId = requireText(id, "Flow id");
        String normalizedCompanyId = requireText(companyId, "Company id");
        Map<String, Object> source = flowDefinitionMap(definition);
        String key = requiredText(source, "key", "Flow");

        Map<String, String> taskIdsByKey = new LinkedHashMap<>();
        long reversion = 1;
        if (latest != null) {
            if (!normalizedId.equals(latest.id)
                || !normalizedCompanyId.equals(latest.companyId)) {
                throw new IllegalArgumentException(
                    "Latest Flow belongs to another logical Flow"
                );
            }
            if (latest.deleted) {
                throw new WorkflowException(
                    "Deleted Flow cannot be deployed: " + normalizedId
                );
            }
            if (!latest.key.equals(key)) {
                throw new WorkflowException(
                    "Flow key cannot change across reversion: "
                        + latest.key + " -> " + key
                );
            }
            latest.allTasks().forEach(task ->
                taskIdsByKey.put(task.key(), task.id())
            );
            reversion = latest.reversion + 1;
        }

        List<Input<?>> inputs = inputList(
            source.get("inputs"),
            "Flow.inputs"
        );
        List<Output> outputs = outputList(
            source.get("outputs"),
            "Flow.outputs"
        );
        List<Task> tasks = materializeTasks(
            source.get("tasks"),
            "Flow.tasks",
            null,
            taskIdsByKey,
            taskTypeDispatcher
        );

        return new Flow(
            normalizedId,
            normalizedCompanyId,
            key,
            reversion,
            optionalText(
                source.get("description"),
                "",
                "Flow.description"
            ),
            inputs,
            outputs,
            tasks,
            false,
            actor,
            actor,
            null,
            deployedAt,
            deployedAt,
            null
        );
    }

    /**
     * Rehydrates a complete Flow from trusted persistence state.
     */
    public static Flow rehydrate(
        String id,
        String companyId,
        String key,
        long reversion,
        String description,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        List<? extends Task> tasks,
        boolean deleted,
        ActorRef creator,
        ActorRef updater,
        ActorRef deleter,
        long createdAt,
        long updatedAt,
        Long deletedAt
    ) {
        return new Flow(
            id,
            companyId,
            key,
            reversion,
            description,
            inputs,
            outputs,
            tasks,
            deleted,
            creator,
            updater,
            deleter,
            createdAt,
            updatedAt,
            deletedAt
        );
    }

    public void delete(ActorRef deletedBy, long deletionTime) {
        if (deleted) {
            throw new WorkflowException("Flow is already deleted: " + id);
        }
        updater = Objects.requireNonNull(deletedBy, "Flow updater");
        deleter = deletedBy;
        updatedAt = deletionTime;
        deletedAt = deletionTime;
        deleted = true;
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String key() {
        return key;
    }

    public long reversion() {
        return reversion;
    }

    public String description() {
        return description;
    }

    public List<Input<?>> inputs() {
        return inputs;
    }

    public List<Output> outputs() {
        return outputs;
    }

    public List<Task> tasks() {
        return tasks;
    }

    public ActorRef creator() {
        return creator;
    }

    public ActorRef updater() {
        return updater;
    }

    public Optional<ActorRef> deleter() {
        return Optional.ofNullable(deleter);
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public Optional<Long> deletedAt() {
        return Optional.ofNullable(deletedAt);
    }

    public Optional<Task> findTask(String taskId) {
        return allTasks().stream()
            .filter(task -> task.id().equals(taskId))
            .findFirst();
    }

    public List<Task> allTasks() {
        return tasks.stream()
            .flatMap(task -> Stream.concat(
                Stream.of(task),
                task.allDescendants().stream()
            ))
            .toList();
    }

    public Flow copy() {
        return rehydrate(
            id,
            companyId,
            key,
            reversion,
            description,
            inputs,
            outputs,
            tasks,
            deleted,
            creator,
            updater,
            deleter,
            createdAt,
            updatedAt,
            deletedAt
        );
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof Flow other)) {
            return false;
        }
        return reversion == other.reversion
            && deleted == other.deleted
            && Objects.equals(id, other.id)
            && Objects.equals(companyId, other.companyId)
            && Objects.equals(key, other.key)
            && Objects.equals(description, other.description)
            && Objects.equals(inputs, other.inputs)
            && Objects.equals(outputs, other.outputs)
            && Objects.equals(tasks, other.tasks)
            && Objects.equals(creator, other.creator)
            && Objects.equals(updater, other.updater)
            && Objects.equals(deleter, other.deleter)
            && Objects.equals(createdAt, other.createdAt)
            && Objects.equals(updatedAt, other.updatedAt)
            && Objects.equals(deletedAt, other.deletedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            id,
            companyId,
            key,
            reversion,
            description,
            inputs,
            outputs,
            tasks,
            deleted,
            creator,
            updater,
            deleter,
            createdAt,
            updatedAt,
            deletedAt
        );
    }

    private static List<Task> materializeTasks(
        Object value,
        String path,
        String parentId,
        Map<String, String> idsByKey,
        TaskTypeDispatcher taskTypeDispatcher
    ) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> definitions)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
        List<Task> tasks = new ArrayList<>(definitions.size());
        for (int index = 0; index < definitions.size(); index++) {
            String taskPath = path + "[" + index + "]";
            Map<String, Object> definition = stringMap(
                definitions.get(index),
                taskPath
            );
            rejectTaskSystemFields(definition, taskPath);
            String taskKey = requiredText(definition, "key", taskPath);
            String taskType = requiredText(
                definition,
                "type",
                taskPath
            ).toUpperCase(Locale.ROOT);
            List<Input<?>> inputs = inputList(
                definition.get("inputs"),
                taskPath + ".inputs"
            );
            List<Output> outputs = outputList(
                definition.get("outputs"),
                taskPath + ".outputs"
            );
            RouteExpression route;
            try {
                route = RouteExpression.parse(optionalText(
                    definition.get("route"),
                    "DIRECT",
                    taskPath + ".route"
                ));
            } catch (RuntimeException exception) {
                throw materializationFailure(taskPath, exception);
            }
            List<String> dependOn = textList(
                definition.get("dependOn"),
                taskPath + ".dependOn"
            );
            Map<String, Object> properties = taskProperties(definition);
            String taskId = idsByKey.computeIfAbsent(
                taskKey,
                ignored -> StringUtil.newId()
            );
            List<Task> children = materializeTasks(
                definition.get("tasks"),
                taskPath + ".tasks",
                taskId,
                idsByKey,
                taskTypeDispatcher
            );
            Task task;
            try {
                task = Objects.requireNonNull(
                    taskTypeDispatcher.dispatch(
                        taskId,
                        parentId,
                        taskKey,
                        taskType,
                        inputs,
                        outputs,
                        route,
                        dependOn,
                        properties,
                        children
                    ),
                    "Task dispatcher result"
                );
            } catch (RuntimeException exception) {
                throw materializationFailure(taskPath, exception);
            }
            if (!taskId.equals(task.id())
                || !Objects.equals(
                    Optional.ofNullable(parentId),
                    task.parentId()
                )
                || !taskKey.equals(task.key())
                || !taskType.equals(task.type())
                || !inputs.equals(task.inputs())
                || !outputs.equals(task.outputs())
                || !route.equals(task.route())
                || !dependOn.equals(task.dependOn())
                || !properties.equals(
                    taskTypeDispatcher.properties(task)
                )
                || !children.equals(task.tasks())) {
                throw new IllegalArgumentException(
                    taskPath
                        + " dispatcher returned an inconsistent Task for "
                        + taskKey
                );
            }
            tasks.add(task);
        }
        return List.copyOf(tasks);
    }

    private static IllegalArgumentException materializationFailure(
        String path,
        RuntimeException exception
    ) {
        String detail = exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
        return new IllegalArgumentException(
            path + " could not be materialized: " + detail,
            exception
        );
    }

    private static Map<String, Object> flowDefinitionMap(
        Map<String, ?> value
    ) {
        Map<String, Object> definition = stringMap(value, "Flow");
        Set<String> unknown = new LinkedHashSet<>(definition.keySet());
        unknown.removeAll(FLOW_DEFINITION_FIELDS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                "Flow contains unsupported fields: " + unknown
            );
        }
        return definition;
    }

    private static void rejectTaskSystemFields(
        Map<String, Object> definition,
        String path
    ) {
        for (String field : TASK_SYSTEM_FIELDS) {
            if (definition.containsKey(field)) {
                throw new IllegalArgumentException(
                    path + " must not declare system field " + field
                );
            }
        }
    }

    private static Map<String, Object> taskProperties(
        Map<String, Object> definition
    ) {
        Map<String, Object> properties = new LinkedHashMap<>();
        definition.forEach((field, value) -> {
            if (!TASK_DEFINITION_FIELDS.contains(field)) {
                properties.put(field, value);
            }
        });
        return Map.copyOf(properties);
    }

    private static List<Input<?>> inputList(Object value, String path) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> source)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
        List<Input<?>> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            String itemPath = path + "[" + index + "]";
            Map<String, Object> definition = stringMap(
                source.get(index),
                itemPath
            );
            String key = requiredText(definition, "key", itemPath);
            DataType type;
            try {
                type = DataType.parse(
                    requiredText(definition, "type", itemPath)
                );
            } catch (IllegalArgumentException exception) {
                throw materializationFailure(itemPath, exception);
            }
            Map<String, Object> normalized = new LinkedHashMap<>(definition);
            normalized.put("type", type.name());
            Object defaultValue = normalized.get("defaultValue");
            if (defaultValue != null) {
                try {
                    normalized.put(
                        "defaultValue",
                        type.normalize(defaultValue)
                    );
                } catch (IllegalArgumentException exception) {
                    throw materializationFailure(itemPath, exception);
                }
            }
            if (!normalized.containsKey("displayName")) {
                normalized.put("displayName", key);
            }
            if (!normalized.containsKey("required")) {
                normalized.put("required", false);
            }
            try {
                result.add(
                    JsonObject.FromMap(normalized).asObject(Input.class)
                );
            } catch (RuntimeException exception) {
                throw materializationFailure(itemPath, exception);
            }
        }
        return List.copyOf(result);
    }

    private static List<Output> outputList(Object value, String path) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> source)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
        List<Output> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            String itemPath = path + "[" + index + "]";
            Map<String, Object> definition = outputDefinitionMap(
                source.get(index),
                itemPath
            );
            DataType type;
            try {
                type = DataType.parse(
                    requiredText(definition, "type", itemPath)
                );
            } catch (IllegalArgumentException exception) {
                throw materializationFailure(itemPath, exception);
            }
            result.add(Output.create(
                requiredText(definition, "key", itemPath),
                type
            ));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> outputDefinitionMap(
        Object value,
        String path
    ) {
        Map<String, Object> definition = stringMap(value, path);
        Set<String> unknown = new LinkedHashSet<>(definition.keySet());
        unknown.removeAll(OUTPUT_DEFINITION_FIELDS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                path + " contains unsupported fields: " + unknown
            );
        }
        return definition;
    }

    private static List<String> textList(Object value, String path) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> source)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
        List<String> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            Object item = source.get(index);
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(
                    path + "[" + index + "] must be non-blank text"
                );
            }
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> stringMap(
        Object value,
        String path
    ) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(path + " must be a map");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, nestedValue) -> {
            if (!(key instanceof String textKey)) {
                throw new IllegalArgumentException(
                    path + " contains a non-text key: " + key
                );
            }
            result.put(textKey, nestedValue);
        });
        return result;
    }

    private static String requiredText(
        Map<String, Object> source,
        String field,
        String path
    ) {
        String value = optionalText(
            source.get(field),
            "",
            path + "." + field
        );
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                path + "." + field + " must not be blank"
            );
        }
        return value;
    }

    private static String optionalText(
        Object value,
        String defaultValue,
        String path
    ) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(path + " must be text");
        }
        return text.trim();
    }

    private static void validateDefinition(List<Task> tasks) {
        if (tasks.isEmpty()) {
            throw new WorkflowException(
                "Flow must contain at least one top-level Task"
            );
        }
        Set<String> keys = new LinkedHashSet<>();
        Set<String> ids = new HashSet<>();
        validateTasks(tasks, keys, ids, null);
        validateDependencies(tasks, keys);
    }

    private static void validateTasks(
        List<Task> tasks,
        Set<String> keys,
        Set<String> ids,
        Task parent
    ) {
        for (Task task : tasks) {
            if (!keys.add(task.key())) {
                throw new WorkflowException("Duplicate Task key: " + task.key());
            }
            if (!ids.add(task.id())) {
                throw new WorkflowException("Duplicate Task id: " + task.id());
            }
            if (parent == null && !task.isTopLevel()) {
                throw new WorkflowException(
                    "Top-level Task parent id must be empty: " + task.key()
                );
            }
            if (parent != null
                && !task.parentId().filter(parent.id()::equals).isPresent()) {
                throw new WorkflowException(
                    "Task parent id does not match its direct parent: "
                        + task.key()
                );
            }
            if (parent == null
                && !RouteExpression.direct().equals(task.route())) {
                throw new WorkflowException(
                    "Top-level Task route must be DIRECT: " + task.key()
                );
            }
            task.route().referencedOutputKey().ifPresent(outputKey -> {
                if (parent == null || !parent.declaresOutput(outputKey)) {
                    throw new WorkflowException(
                        "Task route references undeclared parent output "
                            + outputKey + ": " + task.key()
                    );
                }
                Output routeOutput = parent.outputs().stream()
                    .filter(output -> output.getKey().equals(outputKey))
                    .findFirst()
                    .orElseThrow();
                if (routeOutput.getType() != DataType.STRING) {
                    throw new WorkflowException(
                        "Task route requires a STRING parent output "
                            + outputKey + ": " + task.key()
                    );
                }
            });
            validateTasks(task.tasks(), keys, ids, task);
        }
    }

    private static void validateDependencies(
        List<Task> tasks,
        Set<String> keys
    ) {
        Map<String, Task> tasksByKey = flatten(tasks).stream()
            .collect(Collectors.toMap(
                Task::key,
                task -> task,
                (first, second) -> first,
                LinkedHashMap::new
            ));
        for (Task task : tasksByKey.values()) {
            for (String dependency : task.dependOn()) {
                if (!keys.contains(dependency)) {
                    throw new WorkflowException(
                        "Task dependency does not exist: "
                            + task.key() + " -> " + dependency
                    );
                }
                if (task.key().equals(dependency)) {
                    throw new WorkflowException(
                        "Task cannot depend on itself: " + task.key()
                    );
                }
            }
            detectDependencyCycle(task, tasksByKey, new LinkedHashSet<>());
        }
    }

    private static void detectDependencyCycle(
        Task task,
        Map<String, Task> tasksByKey,
        Set<String> path
    ) {
        if (!path.add(task.key())) {
            throw new WorkflowException(
                "Task dependency cycle contains: " + task.key()
            );
        }
        for (String dependency : task.dependOn()) {
            detectDependencyCycle(
                tasksByKey.get(dependency),
                tasksByKey,
                new LinkedHashSet<>(path)
            );
        }
    }

    private static List<Task> flatten(List<Task> tasks) {
        return tasks.stream()
            .flatMap(task -> Stream.concat(
                Stream.of(task),
                task.allDescendants().stream()
            ))
            .toList();
    }

    private static <T extends Data> List<T> immutableData(
        List<? extends T> source,
        String field
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<T> result = new ArrayList<>(source.size());
        Set<String> keys = new HashSet<>();
        for (T data : source) {
            if (data == null) {
                throw new IllegalArgumentException(
                    field + " must not contain null values"
                );
            }
            if (!keys.add(data.getKey())) {
                throw new IllegalArgumentException(
                    field + " contains duplicate key: " + data.getKey()
                );
            }
            result.add(data);
        }
        return List.copyOf(result);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @Override
    public Flow delete() {
        return null;
    }
}
