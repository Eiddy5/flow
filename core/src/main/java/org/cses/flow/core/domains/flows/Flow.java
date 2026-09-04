package org.cses.flow.core.domains.flows;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.conditions.Condition;
import org.cses.flow.core.domains.conditions.Operand;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.utils.RequiredUtil;
import org.cses.flow.core.utils.SessionUtil;
import org.cses.flow.extensions.flow.Route;
import org.cses.flow.extensions.flow.LoopUntil;
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

    /**
     * Creates a transient deployed definition after validating it against the
     * latest deployed Flow. The Repository assigns its persisted version.
     *
     * @param session non-null tenant and actor creating the definition
     * @param key non-blank stable Flow key
     * @param description description; {@code null} becomes empty text
     * @param variables variables shallow-copied into an unmodifiable map;
     *        {@code null} becomes empty and values remain shared
     * @param inputs inputs shallow-copied into an unmodifiable list;
     *        {@code null} becomes empty and elements remain shared
     * @param outputs outputs shallow-copied into an unmodifiable list;
     *        {@code null} becomes empty and elements remain shared
     * @param tasks executable Task tree shallow-copied into an unmodifiable
     *        list; {@code null} becomes empty and then fails deployed
     *        validation, while elements remain shared
     * @param source non-blank raw source definition retained as supplied
     * @param latest latest deployed Flow, or {@code null} for the first one
     * @return a transient deployed Flow without a persisted version
     * @throws IllegalArgumentException when identity or definition values are
     *         invalid
     * @throws NullPointerException when a copied collection contains a null
     *         element or a variable key is null
     * @throws WorkflowException when the latest state or Task tree rejects the
     *         new definition
     */
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
        validateNextDefinition(
                session,
                normalizedKey,
                boundTasks,
                latest
        );
        return new Flow(
                StringUtil.newId(),
                normalizedKey,
                null,
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

    /**
     * Restores one persisted Flow row and its optional Task snapshot.
     *
     * @param id non-blank database row identifier
     * @param companyId non-blank tenant identifier
     * @param key non-blank stable Flow key
     * @param draft {@code true} for a draft row or {@code false} for a
     *        deployed row
     * @param version positive persisted version
     * @param description description; {@code null} becomes empty text
     * @param variables variables shallow-copied into an unmodifiable map;
     *        {@code null} becomes empty and values remain shared
     * @param inputs inputs shallow-copied into an unmodifiable list;
     *        {@code null} becomes empty and elements remain shared
     * @param outputs outputs shallow-copied into an unmodifiable list;
     *        {@code null} becomes empty and elements remain shared
     * @param tasks Task snapshot shallow-copied into an unmodifiable list;
     *        {@code null} becomes empty and elements remain shared
     * @param status non-null persisted audit status
     * @param creator non-null original creator
     * @param updater non-null latest updater
     * @param deleter deleter, or {@code null} for an active Flow
     * @param createdAt non-negative creation timestamp in milliseconds
     * @param updatedAt latest update timestamp in milliseconds, not before
     *        {@code createdAt}
     * @param deletedAt deletion timestamp in milliseconds, or {@code null}
     *        for an active Flow
     * @param source non-blank raw source definition
     * @return a detached Flow whose maps and lists do not share mutable
     *         containers with the supplied values; contained objects remain
     *         shared
     * @throws IllegalArgumentException when the persisted version is absent or
     *         not positive, or another persisted invariant is invalid
     * @throws NullPointerException when a required object is null or a copied
     *         collection contains a null element
     */
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
        requirePersistedVersion(version);
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
     *
     * @param session non-null tenant and actor initializing the Flow
     * @param draft {@code true} to initialize a draft or {@code false} to
     *        validate and initialize a deployed definition
     * @param latest latest deployed Flow used for compatibility validation,
     *        or {@code null} when no deployed version exists
     * @param rawSource non-blank raw source definition retained as supplied
     * @throws IllegalStateException when this Flow was already initialized
     * @throws IllegalArgumentException when the Session or definition fields
     *         are invalid
     * @throws NullPointerException when a copied Task list contains a null
     *         element
     * @throws WorkflowException when deployed validation fails
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
        if (!draft) {
            rebindTaskIds(latest, boundTasks);
            validateNextDefinition(
                    session,
                    normalizedKey,
                    boundTasks,
                    latest
            );
        }

        initializeAudit(session);
        initializeDefinition(
                normalizedKey,
                null,
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

    /**
     * Validates a deployed definition against the latest deployed Flow.
     * The persisted version is assigned later by the Repository.
     *
     * @param session non-null tenant and actor for the new definition
     * @param flowKey non-blank stable key of the definition
     * @param tasks non-null Task list read without modifying it
     * @param latest latest deployed Flow, or {@code null} for the first one
     * @throws IllegalArgumentException when the latest Flow belongs to another
     *         tenant
     * @throws WorkflowException when the latest Flow is deleted, its key
     *         differs, or a stable Task identity changes
     */
    protected static void validateNextDefinition(
            Session<? extends User> session,
            String flowKey,
            List<Task> tasks,
            Flow latest
    ) {
        String companyId = SessionUtil.company(session);
        if (latest == null) {
            return;
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
    }

    /**
     * Validates that a rehydrated Flow has a positive persisted version.
     *
     * @param version persisted version to validate
     * @throws IllegalArgumentException when the version is absent or not
     *         positive
     */
    private static void requirePersistedVersion(Long version) {
        if (version == null || version < 1) {
            throw new IllegalArgumentException(
                    "Persisted Flow version must be positive"
            );
        }
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
        List<Task> precedingTasks = new ArrayList<>();
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
            if (task instanceof Route route) {
                validateRouteCondition(
                    route,
                    precedingTasks,
                    parent,
                    flowInputs,
                    flowVariables
                );
            } else if (task instanceof LoopUntil loopUntil) {
                validateExternalConditionReferences(
                    loopUntil,
                    loopUntil.condition(),
                    flowInputs,
                    flowVariables
                );
            }
            validateTasks(
                    task.definitionChildren(),
                    keys,
                    ids,
                    task,
                    flowInputs,
                    flowVariables
            );
            precedingTasks.add(task);
        }
    }

    private static void validateRouteCondition(
        Route route,
        List<Task> precedingTasks,
        Task parent,
        List<Input<?>> flowInputs,
        Map<String, Object> flowVariables
    ) {
        Condition condition = route.condition();
        validateExternalConditionReferences(
            route,
            condition,
            flowInputs,
            flowVariables
        );
        for (Operand reference : condition.references()) {
            if (referencesRoot(reference, "outputs")) {
                requireVisibleOutput(
                    route,
                    condition,
                    reference,
                    precedingTasks,
                    parent
                );
            }
        }
    }

    private static void validateExternalConditionReferences(
        Task owner,
        Condition condition,
        List<Input<?>> flowInputs,
        Map<String, Object> flowVariables
    ) {
        if (condition == null) {
            throw new WorkflowException(
                conditionOwner(owner)
                    + " must not be null: " + owner.key()
            );
        }
        for (Operand reference : condition.references()) {
            if (referencesRoot(reference, "vars")) {
                requireDeclaredVariable(
                    owner,
                    condition,
                    reference,
                    flowVariables
                );
            } else if (referencesRoot(reference, "inputs")) {
                requireDeclaredInput(
                    owner,
                    condition,
                    reference,
                    flowInputs
                );
            }
        }
    }

    private static void requireDeclaredVariable(
        Task owner,
        Condition condition,
        Operand reference,
        Map<String, Object> flowVariables
    ) {
        List<String> path = reference.path();
        if (path.size() < 2) {
            throw new WorkflowException(
                conditionOwner(owner)
                    + " Flow variable path must contain a key: "
                    + reference
            );
        }
        Object value = flowVariables;
        for (String segment : path.subList(1, path.size())) {
            if (!(value instanceof Map<?, ?> values)
                || !values.containsKey(segment)) {
                throw new WorkflowException(
                    conditionOwner(owner)
                        + " references undeclared Flow variable "
                        + reference + ": " + owner.key()
                );
            }
            value = values.get(segment);
        }
        DataType type = dataType(value);
        if (type != null && !condition.supports(reference, type)) {
            throw new WorkflowException(
                conditionOwner(owner)
                    + " is incompatible with Flow variable "
                    + reference + ": " + owner.key()
            );
        }
    }

    private static void requireDeclaredInput(
        Task owner,
        Condition condition,
        Operand reference,
        List<Input<?>> flowInputs
    ) {
        if (reference.path().size() != 2) {
            throw new WorkflowException(
                conditionOwner(owner)
                    + " Flow input path must contain one key: "
                    + reference
            );
        }
        String key = reference.path().get(1);
        Input<?> input = flowInputs.stream()
            .filter(candidate -> candidate.getKey().equals(key))
            .findFirst()
            .orElseThrow(() -> new WorkflowException(
                conditionOwner(owner)
                    + " references undeclared Flow input "
                    + key + ": " + owner.key()
            ));
        if (!condition.supports(reference, input.getType())) {
            throw new WorkflowException(
                conditionOwner(owner)
                    + " is incompatible with Flow input "
                    + key + ": " + owner.key()
            );
        }
    }

    private static DataType dataType(Object value) {
        if (value instanceof String) return DataType.STRING;
        if (value instanceof Character) return DataType.CHARACTER;
        if (value instanceof Boolean) return DataType.BOOLEAN;
        if (value instanceof Byte) return DataType.BYTE;
        if (value instanceof Short) return DataType.SHORT;
        if (value instanceof Integer) return DataType.INTEGER;
        if (value instanceof Long) return DataType.LONG;
        if (value instanceof Float) return DataType.FLOAT;
        if (value instanceof Number) return DataType.DOUBLE;
        return null;
    }

    private static String conditionOwner(Task owner) {
        return owner instanceof Route
            ? "Route.route"
            : owner.getClass().getSimpleName() + " condition";
    }

    private static void requireVisibleOutput(
        Route route,
        Condition condition,
        Operand reference,
        List<Task> precedingTasks,
        Task parent
    ) {
        if (parent instanceof OrchestrationTask orchestrationTask
            && orchestrationTask.startsChildrenInParallel()) {
            throw new WorkflowException(
                "Route.route cannot read sibling outputs in a parallel scope: "
                    + route.key()
            );
        }
        if (reference.path().size() != 3) {
            throw new WorkflowException(
                "Route.route output path must be taskKey.outputKey: "
                    + reference
            );
        }
        String taskKey = reference.path().get(1);
        String outputKey = reference.path().get(2);
        Task source = precedingTasks.stream()
            .filter(candidate -> candidate.key().equals(taskKey))
            .findFirst()
            .orElseThrow(() -> new WorkflowException(
                "Route.route references an unavailable output Task "
                    + taskKey + ": " + route.key()
            ));
        Output output = source.outputs().stream()
            .filter(candidate -> candidate.getKey().equals(outputKey))
            .findFirst()
            .orElseThrow(() -> new WorkflowException(
                "Route.route references an undeclared output "
                    + taskKey + "." + outputKey + ": " + route.key()
            ));
        if (!condition.supports(reference, output.getType())) {
            throw new WorkflowException(
                "Route.route is incompatible with output "
                    + taskKey + "." + outputKey + ": " + route.key()
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

    private static boolean referencesRoot(
        Operand reference,
        String root
    ) {
        return !reference.path().isEmpty()
            && reference.path().getFirst().equals(root);
    }

    protected static String requireText(String value, String field) {
        return RequiredUtil.required(value, field + " must not be blank")
                .trim();
    }

}
