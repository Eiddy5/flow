package org.cses.flow.core.domains.flows;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.TaskRoute;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.utils.RequiredUtil;
import org.cses.flow.core.utils.SessionUtil;
import org.paas.common.util.StringUtil;
import org.paas.session.RecordState;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Concrete Flow aggregate for editable drafts and deployed versions.
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Flow extends AbstractFlow {

    List<Task> tasks = List.of();
    String source;

    private Flow(
        String id,
        String key,
        Long version,
        boolean draft,
        Session<? extends User> session,
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        List<? extends Task> tasks,
        String source
    ) {
        super(
            id,
            key,
            version,
            draft,
            session,
            description,
            variables,
            inputs,
            outputs
        );
        this.tasks = immutableTasks(tasks);
        this.source = RequiredUtil.required(source, "Flow source");
        validateTasksForState();
    }

    private Flow(
        String id,
        String key,
        Long version,
        boolean draft,
        String companyId,
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        List<? extends Task> tasks,
        RecordState status,
        ActorRef creator,
        ActorRef updater,
        ActorRef deleter,
        long createdAt,
        long updatedAt,
        Long deletedAt,
        String source
    ) {
        super(
            id,
            key,
            version,
            draft,
            companyId,
            description,
            variables,
            inputs,
            outputs,
            creator,
            createdAt,
            status,
            updater,
            updatedAt,
            deleter,
            deletedAt
        );
        this.tasks = immutableTasks(tasks);
        this.source = RequiredUtil.required(source, "Flow source");
        validateTasksForState();
    }
    public static Flow create(
        Session<? extends User> session,
        String key,
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        String source
    ) {
        return create(
            session,
            key,
            description,
            variables,
            inputs,
            outputs,
            List.of(),
            source
        );
    }

    public static Flow create(
        Session<? extends User> session,
        String key,
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        List<? extends Task> tasks,
        String source
    ) {
        return new Flow(
            StringUtil.newId(),
            key,
            null,
            true,
            session,
            description,
            variables,
            inputs,
            outputs,
            tasks,
            source
        );
    }

    public static Flow deploy(
        Session<? extends User> session,
        String key,
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        List<? extends Task> tasks,
        String source,
        Flow latest
    ) {
        String normalizedKey = requireText(key, "Flow key");
        List<Task> boundTasks = tasks == null
            ? List.of()
            : List.copyOf(tasks);
        long version = nextVersion(
            session,
            normalizedKey,
            boundTasks,
            latest
        );
        return new Flow(
            StringUtil.newId(),
            normalizedKey,
            version,
            false,
            session,
            description,
            variables,
            inputs,
            outputs,
            boundTasks,
            source
        );
    }

    public static Flow rehydrate(
        String id,
        String companyId,
        String key,
        boolean draft,
        Long version,
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        List<? extends Task> tasks,
        RecordState status,
        ActorRef creator,
        ActorRef updater,
        ActorRef deleter,
        long createdAt,
        long updatedAt,
        Long deletedAt,
        String source
    ) {
        return new Flow(
            id,
            key,
            version,
            draft,
            companyId,
            description,
            variables,
            inputs,
            outputs,
            tasks,
            status,
            creator,
            updater,
            deleter,
            createdAt,
            updatedAt,
            deletedAt,
            source
        );
    }

    /**
     * Completes a Flow created by Jackson's no-args binding path.
     * YAML supplies the definition; this method supplies identity, tenant,
     * audit, lifecycle and raw source facts.
     */
    public void initialize(
        Session<? extends User> session,
        boolean draft,
        Flow latest,
        String rawSource
    ) {
        if (id() != null) {
            throw new IllegalStateException("Flow is already initialized");
        }
        String normalizedKey = requireText(key, "Flow key");
        List<Task> boundTasks = immutableTasks(tasks);
        long nextVersion = 0;
        if (!draft) {
            rebindTaskIds(latest, boundTasks);
            nextVersion = nextVersion(
                session,
                normalizedKey,
                boundTasks,
                latest
            );
        }

        initializeAudit(session);
        initializeDefinition(
            normalizedKey,
            draft ? null : nextVersion,
            draft,
            description,
            variables,
            inputs,
            outputs
        );
        tasks = boundTasks;
        source = RequiredUtil.required(rawSource, "Flow source");
        validateTasksForState();
    }

    public String resolveKey(String suppliedKey) {
        if (id() != null) {
            throw new IllegalStateException("Flow is already initialized");
        }
        String supplied = suppliedKey == null || suppliedKey.isBlank()
            ? null
            : suppliedKey.trim();
        if (key == null || key.isBlank()) {
            if (supplied == null) {
                throw new IllegalArgumentException(
                    "Flow key or top-level YAML key must be provided"
                );
            }
            key = supplied;
            return key;
        }
        String declared = key.trim();
        if (supplied != null && !supplied.equals(declared)) {
            throw new WorkflowException(
                "Flow key does not match YAML: "
                    + supplied + " -> " + declared
            );
        }
        key = declared;
        return key;
    }

    public List<Task> tasks() {
        return tasks;
    }

    public String source() {
        return source;
    }

    public void revise(
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        String revisedSource,
        Session<? extends User> session,
        long revisedAt
    ) {
        revise(
            description,
            variables,
            inputs,
            outputs,
            tasks,
            revisedSource,
            session,
            revisedAt
        );
    }

    public void revise(
        String description,
        Map<String, ?> variables,
        List<? extends Input<?>> inputs,
        List<? extends Output> outputs,
        List<? extends Task> revisedTasks,
        String revisedSource,
        Session<? extends User> session,
        long revisedAt
    ) {
        requireDraft();
        String checkedSource = RequiredUtil.required(
            revisedSource,
            "Flow source"
        );
        reviseDraftDefinition(
            session,
            revisedAt,
            description,
            variables,
            inputs,
            outputs
        );
        List<Task> boundTasks = immutableTasks(revisedTasks);
        rebindTaskIds(this, boundTasks);
        tasks = boundTasks;
        source = checkedSource;
    }

    /**
     * Returns a detached copy while preserving the complete Flow state.
     */
    public Flow copy() {
        return rehydrate(
            id(),
            companyId(),
            key(),
            draft(),
            versionOrNull(),
            description(),
            variables(),
            inputs(),
            outputs(),
            tasks,
            status(),
            creator(),
            updater(),
            deleter().orElse(null),
            createdAt(),
            updatedAt(),
            deletedAt().orElse(null),
            source
        );
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
        requireDeployed();
        Map<String, ?> accepted = actualInputs == null
                ? Map.of()
                : actualInputs;
        Set<String> declared = inputs().stream()
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
        for (Input<?> input : inputs()) {
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

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof Flow other)) {
            return false;
        }
        return createdAt() == other.createdAt()
                && updatedAt() == other.updatedAt()
                && Objects.equals(id(), other.id())
                && Objects.equals(companyId(), other.companyId())
                && Objects.equals(key(), other.key())
                && Objects.equals(versionOrNull(), other.versionOrNull())
                && draft() == other.draft()
                && Objects.equals(description(), other.description())
                && Objects.equals(variables(), other.variables())
                && Objects.equals(inputs(), other.inputs())
                && Objects.equals(outputs(), other.outputs())
                && Objects.equals(tasks, other.tasks)
                && Objects.equals(source, other.source)
                && Objects.equals(status(), other.status())
                && Objects.equals(creator(), other.creator())
                && Objects.equals(updater(), other.updater())
                && Objects.equals(deleter(), other.deleter())
                && Objects.equals(deletedAt(), other.deletedAt());
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                id(),
                companyId(),
                key(),
                versionOrNull(),
                draft(),
                description(),
                variables(),
                inputs(),
                outputs(),
                tasks,
                source,
                status(),
                creator(),
                updater(),
                deleter(),
                createdAt(),
                updatedAt(),
                deletedAt()
        );
    }

    protected static long nextVersion(
        Session<? extends User> session,
        String flowKey,
        List<Task> tasks,
        Flow latest
    ) {
        String companyId = SessionUtil.company(session);
        if (latest == null) {
            return 1;
        }
        if (!companyId.equals(latest.companyId())) {
            throw new IllegalArgumentException(
                "Latest Flow belongs to another logical Flow"
            );
        }
        if (latest.deleted()) {
            throw new WorkflowException(
                "Deleted Flow cannot be deployed: " + flowKey
            );
        }
        if (!latest.key().equals(flowKey)) {
            throw new WorkflowException(
                "Flow key cannot change across reversion: "
                    + latest.key() + " -> " + flowKey
            );
        }
        requireStableTaskIds(latest, tasks);
        return latest.version() + 1;
    }

    protected static void requireStableTaskIds(
            Flow latest,
            List<Task> tasks
    ) {
        Map<String, String> previousIdByKey = latest.allTasks().stream()
                .collect(Collectors.toMap(Task::key, Task::id));
        Map<String, String> previousKeyById = latest.allTasks().stream()
                .collect(Collectors.toMap(Task::id, Task::key));
        for (Task task : flatten(tasks)) {
            String previousId = previousIdByKey.get(task.key());
            if (previousId != null
                && !task.identifiedBy(previousId)) {
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

    protected static void validateDefinition(
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

    private void validateTasksForState() {
        if (draft()) {
            return;
        }
        validateDefinition(variables(), inputs(), tasks);
    }

    private static void rebindTaskIds(Flow previous, List<Task> tasks) {
        if (previous == null) {
            return;
        }
        Map<String, String> previousIdByKey = previous.allTasks().stream()
            .collect(Collectors.toMap(Task::key, Task::id));
        rebindTaskIds(tasks, previousIdByKey);
    }

    private static void rebindTaskIds(
        List<Task> tasks,
        Map<String, String> previousIdByKey
    ) {
        for (Task task : tasks) {
            String previousId = previousIdByKey.get(task.key());
            if (previousId != null) {
                task.reidentify(previousId);
            }
            rebindTaskIds(task.definitionChildren(), previousIdByKey);
        }
    }

    private static List<Task> immutableTasks(
        List<? extends Task> source
    ) {
        return source == null || source.isEmpty()
            ? List.of()
            : List.copyOf(source);
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

    protected static String requireText(String value, String field) {
        return RequiredUtil.required(value, field + " must not be blank")
            .trim();
    }

}
