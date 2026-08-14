package org.cses.flow.core.domains.flows;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.Deletable;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.TaskRoute;
import org.cses.flow.core.exceptions.WorkflowException;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One complete, deployed Flow reversion.
 */
public final class Flow implements Deletable<Flow> {

    private final String id;
    private final String companyId;
    private final String key;
    private final long reversion;
    private final String description;
    private final Map<String, Object> variables;
    private final List<Input<?>> inputs;
    private final List<Output> outputs;
    private final List<Task> tasks;
    private final ActorRef creator;
    private final long createdAt;
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
            Map<String, ?> variables,
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
        this.variables = immutableVariables(variables);
        this.inputs = immutableData(inputs, "Flow inputs");
        this.outputs = immutableData(outputs, "Flow outputs");
        this.tasks = tasks == null ? List.of() : List.copyOf(tasks);
        validateDefinition(this.variables, this.inputs, this.tasks);
        this.creator = Objects.requireNonNull(creator, "Flow creator");
        this.updater = Objects.requireNonNull(updater, "Flow updater");
        if (createdAt < 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("Flow timestamps are invalid");
        }
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
     * Creates one complete Flow reversion from already-bound definitions.
     */
    public static Flow deploy(
        String companyId,
        String id,
        String key,
        String description,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        List<? extends Task> tasks,
        Flow latest,
        ActorRef actor,
        long deployedAt
    ) {
        return deploy(
            companyId,
            id,
            key,
            description,
            Map.of(),
            inputs,
            outputs,
            tasks,
            latest,
            actor,
            deployedAt
        );
    }

    /**
     * Creates one complete Flow reversion with Flow-level variables.
     */
    public static Flow deploy(
        String companyId,
        String id,
        String key,
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        List<? extends Task> tasks,
        Flow latest,
        ActorRef actor,
        long deployedAt
    ) {
        Objects.requireNonNull(actor, "Flow creator");
        String normalizedId = requireText(id, "Flow id");
        String normalizedCompanyId = requireText(companyId, "Company id");
        String normalizedKey = requireText(key, "Flow key");
        List<Task> boundTasks = tasks == null ? List.of() : List.copyOf(tasks);

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
            if (!latest.key.equals(normalizedKey)) {
                throw new WorkflowException(
                        "Flow key cannot change across reversion: "
                                + latest.key + " -> " + normalizedKey
                );
            }
            requireStableTaskIds(latest, boundTasks);
            reversion = latest.reversion + 1;
        }

        return new Flow(
                normalizedId,
                normalizedCompanyId,
                normalizedKey,
                reversion,
                description,
                variables,
                inputs,
                outputs,
                boundTasks,
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
        return rehydrate(
            id,
            companyId,
            key,
            reversion,
            description,
            Map.of(),
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

    /**
     * Rehydrates a complete Flow with persisted Flow-level variables.
     */
    public static Flow rehydrate(
        String id,
        String companyId,
        String key,
        long reversion,
        String description,
        Map<String, ?> variables,
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
                variables,
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
    public Flow delete(
        Session<? extends User> session,
        long deletionTime
    ) {
        requireSessionCompany(session);
        return delete(ActorRef.from(session), deletionTime);
    }

    public Flow delete(ActorRef deletedBy, long deletionTime) {
        if (deleted) {
            throw new WorkflowException("Flow is already deleted: " + id);
        }
        ActorRef actor = Objects.requireNonNull(deletedBy, "Flow deleter");
        updateAudit(actor, deletionTime);
        deleter = actor;
        deletedAt = deletionTime;
        deleted = true;
        return this;
    }

    @Override
    public Flow updateAudit(
        Session<? extends User> session,
        long updateTime
    ) {
        requireSessionCompany(session);
        return updateAudit(ActorRef.from(session), updateTime);
    }

    public Flow updateAudit(ActorRef updatedBy, long updateTime) {
        if (deleted) {
            throw new WorkflowException(
                "Deleted Flow cannot be changed: " + id
            );
        }
        if (updateTime < updatedAt) {
            throw new IllegalArgumentException(
                "Flow update time must not move backwards"
            );
        }
        updater = Objects.requireNonNull(updatedBy, "Flow updater");
        updatedAt = updateTime;
        return this;
    }

    public String id() {
        return id;
    }

    @Override
    public String identifier() {
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

    /**
     * Returns the immutable Flow-level variable snapshot.
     */
    public Map<String, Object> variables() {
        return variables;
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

    @Override
    public boolean isDeleted() {
        return deleted;
    }

    @Override
    public ActorRef creator() {
        return creator;
    }

    @Override
    public ActorRef updater() {
        return updater;
    }

    @Override
    public Optional<ActorRef> deleter() {
        return Optional.ofNullable(deleter);
    }

    @Override
    public long createdAt() {
        return createdAt;
    }

    @Override
    public long updatedAt() {
        return updatedAt;
    }

    @Override
    public Optional<Long> deletedAt() {
        return Optional.ofNullable(deletedAt);
    }

    public Optional<Task> findTask(String taskId) {
        return allTasks().stream()
                .filter(task -> task.identifiedBy(taskId))
                .findFirst();
    }

    public List<Task> allTasks() {
        return flatten(tasks);
    }

    /**
     * Validates and normalizes one confirmed Flow input payload.
     */
    public Map<String, Object> normalizeInputs(Map<String, ?> actualInputs) {
        Map<String, ?> accepted = actualInputs == null
            ? Map.of()
            : actualInputs;
        Set<String> declared = inputs.stream()
            .map(Input::getKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> unsupported = new LinkedHashSet<>(accepted.keySet());
        unsupported.removeAll(declared);
        if (!unsupported.isEmpty()) {
            throw new WorkflowException(
                "Flow inputs were not declared: " + unsupported
            );
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Input<?> input : inputs) {
            boolean provided = accepted.containsKey(input.getKey());
            Object value = provided
                ? accepted.get(input.getKey())
                : input.getDefaultValue();
            try {
                Object result = input.normalized(value);
                if (result != null) {
                    normalized.put(input.getKey(), result);
                }
            } catch (IllegalArgumentException exception) {
                throw new WorkflowException(exception.getMessage());
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    public Flow copy() {
        return rehydrate(
                id,
                companyId,
                key,
                reversion,
                description,
                variables,
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
                && Objects.equals(variables, other.variables)
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
                variables,
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

    private static void requireStableTaskIds(
            Flow latest,
            List<Task> tasks
    ) {
        Map<String, String> previousIdByKey = latest.allTasks().stream()
                .collect(Collectors.toMap(Task::key, Task::id));
        Map<String, String> previousKeyById = latest.allTasks().stream()
                .collect(Collectors.toMap(Task::id, Task::key));
        for (Task task : flatten(tasks)) {
            String previousId = previousIdByKey.get(task.key());
            if (previousId != null && !task.identifiedBy(previousId)) {
                throw new WorkflowException(
                        "Task id cannot change across reversion: " + task.key()
                );
            }
            String previousKey = previousKeyById.get(task.id());
            if (previousKey != null && !previousKey.equals(task.key())) {
                throw new WorkflowException(
                        "Task id cannot move to another key: "
                                + previousKey + " -> " + task.key()
                );
            }
        }
    }

    private static void validateDefinition(
            Map<String, Object> flowVariables,
            List<Input<?>> flowInputs,
            List<Task> tasks
    ) {
        if (tasks.isEmpty()) {
            throw new WorkflowException(
                    "Flow must contain at least one top-level Task"
            );
        }
        Set<String> keys = new LinkedHashSet<>();
        Set<String> ids = new HashSet<>();
        validateTasks(
                tasks,
                keys,
                ids,
                null,
                flowInputs,
                flowVariables
        );
        validateDependencies(tasks, keys);
    }

    private static void validateTasks(
            List<Task> tasks,
            Set<String> keys,
            Set<String> ids,
            Task parent,
            List<Input<?>> flowInputs,
            Map<String, Object> flowVariables
    ) {
        for (Task task : tasks) {
            if (task == null) {
                throw new WorkflowException(
                        "Flow tasks must not contain null values"
                );
            }
            String taskKey = requireText(task.key(), "Task key");
            String taskId = requireText(task.id(), "Task id");
            if (!keys.add(taskKey)) {
                throw new WorkflowException("Duplicate Task key: " + taskKey);
            }
            if (!ids.add(taskId)) {
                throw new WorkflowException("Duplicate Task id: " + taskId);
            }
            if (parent == null
                    && !TaskRoute.direct().equals(task.route())) {
                throw new WorkflowException(
                        "Top-level Task route must be DIRECT: " + taskKey
                );
            }
            if (task.route() == null) {
                throw new WorkflowException(
                        "Task route must not be null: " + taskKey
                );
            }
            boolean ordinaryChild = parent == null
                    || parent.tasks().contains(task);
            if (ordinaryChild) {
                task.route().referencedOutputKey().ifPresent(outputKey -> {
                    boolean parallelInput =
                            parent instanceof OrchestrationTask orchestrationTask
                                    && orchestrationTask
                                    .startsChildrenInParallel();
                    boolean declared = parent != null
                            && (parallelInput
                            ? parent.declaresInput(outputKey)
                            : parent.declaresOutput(outputKey));
                    if (!declared) {
                        throw new WorkflowException(
                                "Task route references undeclared parent context "
                                        + outputKey + ": " + taskKey
                        );
                    }
                    Data routeData = (parallelInput
                            ? parent.inputs().stream()
                            : parent.outputs().stream())
                            .filter(data -> data.getKey().equals(outputKey))
                            .findFirst()
                            .orElseThrow();
                    if (!task.route().supports(routeData.getType())) {
                        throw new WorkflowException(
                                "Task route is incompatible with parent context "
                                        + outputKey + ": " + taskKey
                        );
                    }
                });
                task.route().referencedInputKey().ifPresent(inputKey -> {
                    Input<?> flowInput = flowInputs.stream()
                        .filter(input -> input.getKey().equals(inputKey))
                        .findFirst()
                        .orElseThrow(() -> new WorkflowException(
                            "Task route references undeclared Flow input "
                                + inputKey + ": " + taskKey
                        ));
                    if (!task.route().supports(flowInput.getType())) {
                        throw new WorkflowException(
                            "Task route is incompatible with Flow input "
                                + inputKey + ": " + taskKey
                        );
                    }
                });
            }
            task.route().referencedVariableKey().ifPresent(variableKey -> {
                if (!flowVariables.containsKey(variableKey)) {
                    throw new WorkflowException(
                            "Task route references undeclared Flow variable "
                                    + variableKey + ": " + taskKey
                    );
                }
            });
            validateTasks(
                task.definitionChildren(),
                keys,
                ids,
                task,
                flowInputs,
                flowVariables
            );
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

    private static Map<String, Object> immutableVariables(
            Map<String, ?> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(
                Objects.requireNonNull(key, "Flow variable key"),
                value
        ));
        return Collections.unmodifiableMap(result);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private void requireSessionCompany(Session<? extends User> session) {
        Objects.requireNonNull(session, "Session");
        String sessionCompanyId = requireText(
            session.getCompanyId(),
            "Session company id"
        );
        if (!companyId.equals(sessionCompanyId)) {
            throw new WorkflowException(
                "Session company cannot change Flow " + id
            );
        }
    }
}
