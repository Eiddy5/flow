package org.cses.flow.core.domains.executions;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.BaseDomain;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.utils.RequiredUtil;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.*;

/**
 * Aggregate root for one complete Flow start instance.
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Execution extends BaseDomain {

    String flowKey;
    Long flowVersion;
    List<TaskRun> taskRuns;
    State state;
    Map<String, Object> inputs;

    private Execution(
            String id,
            Session<? extends User> session,
            String flowKey,
            long flowVersion,
            Map<String, ?> inputs
    ) {
        super(id, session);
        this.flowKey = requireText(flowKey, "Flow key");
        if (flowVersion < 1) {
            throw new IllegalArgumentException("Flow version must be positive");
        }
        this.flowVersion = flowVersion;
        this.taskRuns = new ArrayList<>();
        this.inputs = immutableMap(inputs);
        this.state = State.created();
    }

    private Execution(
            String id,
            String companyId,
            ActorRef creator,
            long createdAt,
            String flowKey,
            long flowVersion,
            Map<String, ?> inputs,
            State state,
            List<TaskRun> taskRuns
    ) {
        super(id, companyId, creator, createdAt);
        this.flowKey = requireText(flowKey, "Flow key");
        if (flowVersion < 1) {
            throw new IllegalArgumentException("Flow version must be positive");
        }
        this.flowVersion = flowVersion;
        this.inputs = immutableMap(inputs);
        this.state = RequiredUtil.required(state, "Execution state");
        this.taskRuns = new ArrayList<>();
        if (taskRuns != null) {
            taskRuns.stream().map(TaskRun::copy).forEach(this.taskRuns::add);
        }
        validateRehydratedState();
    }

    public static Execution create(
            String requestedId,
            Session<? extends User> session,
            String flowKey,
            long flowVersion,
            Map<String, ?> inputs
    ) {
        String id = requestedId == null || requestedId.isBlank()
                ? StringUtil.newId()
                : requestedId;
        return new Execution(
                id,
                session,
                flowKey,
                flowVersion,
                inputs
        );
    }

    public static Execution rehydrate(
            String id,
            String companyId,
            ActorRef creator,
            long createdAt,
            String flowKey,
            long flowVersion,
            Map<String, ?> inputs,
            State state,
            List<TaskRun> taskRuns
    ) {
        return new Execution(
                id,
                companyId,
                creator,
                createdAt,
                flowKey,
                flowVersion,
                inputs,
                state,
                taskRuns
        );
    }

    public String flowKey() {
        return flowKey;
    }

    public long flowVersion() {
        return flowVersion;
    }

    /**
     * Returns the normalized values supplied for the exact Flow version.
     */
    public Map<String, Object> inputs() {
        return inputs;
    }

    public State state() {
        return state;
    }

    public List<TaskRun> taskRuns() {
        return List.copyOf(taskRuns);
    }

    public Optional<TaskRun> findTaskRun(String taskRunId) {
        String normalizedId = requireText(taskRunId, "TaskRun id");
        return taskRuns.stream()
                .filter(taskRun -> taskRun.identifiedBy(normalizedId))
                .findFirst();
    }

    public List<TaskRun> taskRunsForTask(String taskId) {
        String normalizedId = requireText(taskId, "Task id");
        return taskRuns.stream().filter(taskRun -> taskRun.taskId().equals(normalizedId)).toList();
    }

    public Optional<TaskRun> latestTaskRunForTask(String taskId) {
        List<TaskRun> matches = taskRunsForTask(taskId);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getLast());
    }

    /**
     * Locates the TaskRun for one concrete definition occurrence.
     */
    public Optional<TaskRun> taskRunForOccurrence(String taskId, String parentTaskRunId, Integer iteration) {
        String normalizedTaskId = requireText(taskId, "Task id");
        return taskRuns.stream().filter(taskRun -> taskRun.taskId().equals(normalizedTaskId)).filter(taskRun -> Objects.equals(taskRun.parentId().orElse(null), parentTaskRunId)).filter(taskRun -> Objects.equals(taskRun.iteration().isPresent() ? taskRun.iteration().getAsInt() : null, iteration)).findFirst();
    }

    public List<TaskRun> activeTaskRuns() {
        return taskRuns.stream().filter(TaskRun::isActive).toList();
    }

    public List<TaskRun> pausedTaskRuns() {
        return taskRuns.stream().filter(TaskRun::isPaused).toList();
    }

    public List<TaskRun> unfinishedTaskRuns() {
        return taskRuns.stream().filter(TaskRun::isUnfinished).toList();
    }

    public Optional<TaskRun> lastTaskRun() {
        return taskRuns.isEmpty() ? Optional.empty() : Optional.of(taskRuns.getLast());
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    public void start() {
        requireState(State.Type.CREATED);
        state = state.running();
    }

    /**
     * Starts a new Execution and accepts its first scheduling batch atomically.
     */
    public void startWithTaskRuns(List<TaskRun> nexts) {
        requireState(State.Type.CREATED);
        List<TaskRun> accepted = validatedTaskRuns(nexts);
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("The first TaskRun batch must not be empty");
        }
        state = state.running();
        taskRuns.addAll(accepted);
    }

    public TaskRun createTaskRun(String taskId, String parentTaskRunId, Map<String, ?> inputs) {
        TaskRun taskRun = TaskRun.create(taskId, parentTaskRunId, inputs);
        addTaskRuns(List.of(taskRun));
        return requireTaskRun(taskRun.id());
    }

    /**
     * Accepts one validated scheduling batch into this aggregate.
     *
     * <p>The complete batch is validated before any TaskRun is appended, so a
     * rejected batch cannot partially change the Execution.</p>
     */
    public void addTaskRuns(List<TaskRun> nexts) {
        requireRunning();
        List<TaskRun> accepted = validatedTaskRuns(nexts);
        if (accepted.isEmpty()) {
            return;
        }
        taskRuns.addAll(accepted);
    }

    private List<TaskRun> validatedTaskRuns(List<TaskRun> nexts) {
        Objects.requireNonNull(nexts, "Next TaskRuns");
        if (nexts.isEmpty()) {
            return List.of();
        }

        HashSet<String> taskRunIds = taskRuns.stream().map(TaskRun::id).collect(java.util.stream.Collectors.toCollection(HashSet::new));
        HashSet<TaskOccurrence> occurrences = taskRuns.stream().map(TaskOccurrence::from).collect(java.util.stream.Collectors.toCollection(HashSet::new));
        List<TaskRun> accepted = new ArrayList<>(nexts.size());
        for (TaskRun next : nexts) {
            TaskRun taskRun = Objects.requireNonNull(next, "Next TaskRun");
            if (!taskRun.state().is(State.Type.CREATED) || !taskRun.outputs().isEmpty() || taskRun.error().isPresent()) {
                throw new WorkflowException("Only a new CREATED TaskRun can be scheduled: " + taskRun.id());
            }
            if (taskRunIds.contains(taskRun.id())) {
                throw new WorkflowException("Execution already has TaskRun id: " + taskRun.id());
            }
            if (!occurrences.add(TaskOccurrence.from(taskRun))) {
                throw new WorkflowException("Execution already has this TaskRun occurrence: " + taskRun.taskId());
            }
            taskRun.parentId().ifPresent(parentTaskRunId -> {
                if (!taskRunIds.contains(parentTaskRunId)) {
                    throw new WorkflowException("Parent TaskRun does not exist: " + parentTaskRunId);
                }
            });
            taskRunIds.add(taskRun.id());
            accepted.add(taskRun.copy());
        }
        return accepted;
    }

    public void startTaskRun(String taskRunId) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        taskRun.start();
    }

    public void succeedTaskRun(String taskRunId, Map<String, ?> outputs) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
        taskRun.succeed(outputs);
    }

    public void warnTaskRun(String taskRunId, Map<String, ?> outputs) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
        taskRun.warn(outputs);
    }

    public void pauseTaskRun(String taskRunId) {
        pauseTaskRuns(List.of(taskRunId));
    }

    public void pauseTaskRuns(List<String> taskRunIds) {
        requireRunning();
        Objects.requireNonNull(taskRunIds, "TaskRun ids");
        List<TaskRun> pausing = taskRunIds.stream().map(this::requireTaskRun).toList();
        if (new HashSet<>(pausing).size() != pausing.size()) {
            throw new IllegalArgumentException("TaskRun ids to pause must be unique");
        }
        pausing.forEach(taskRun -> requireTaskRunState(taskRun, State.Type.RUNNING));
        if (pausing.isEmpty()) {
            return;
        }
        pausing.forEach(TaskRun::pause);
    }

    public void resumeTaskRun(String taskRunId, Map<String, ?> outputs) {
        requireState(State.Type.PAUSED);
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.PAUSED);
        taskRun.resume(outputs);
        resumePausedAncestors(taskRun);
        state = state.restarted();
    }

    private void resumePausedAncestors(TaskRun taskRun) {
        Optional<String> parentTaskRunId = taskRun.parentId();
        while (parentTaskRunId.isPresent()) {
            TaskRun parent = requireTaskRun(parentTaskRunId.orElseThrow());
            if (parent.state().is(State.Type.PAUSED)) {
                parent.resume(parent.outputs());
            }
            parentTaskRunId = parent.parentId();
        }
    }

    public Execution restart() {
        requireState(State.Type.RESTARTED);
        state = state.running();
        return this;
    }

    public void failTaskRun(String taskRunId, String error) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
        if (error == null || error.isBlank()) {
            throw new IllegalArgumentException("TaskRun error must not be blank");
        }
        taskRun.fail(error);
        taskRuns.stream().filter(other -> other != taskRun).filter(TaskRun::isUnfinished).forEach(TaskRun::kill);
        state = state.failed();
    }

    public void succeed() {
        requireRunning();
        if (!unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException(
                    "Execution has unfinished TaskRun: " + id()
            );
        }
        state = state.success();
    }

    public void warn() {
        requireRunning();
        if (!unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException(
                    "Execution has unfinished TaskRun: " + id()
            );
        }
        if (taskRuns.stream().noneMatch(taskRun -> taskRun.state().is(State.Type.WARNING))) {
            throw new WorkflowException(
                    "Execution cannot finish with WARNING without a warning "
                            + "TaskRun: " + id()
            );
        }
        state = state.warning();
    }

    public void pause() {
        requireRunning();
        if (!activeTaskRuns().isEmpty() || pausedTaskRuns().isEmpty()) {
            throw new WorkflowException(
                    "Execution can pause only at a stable Pause TaskRun: "
                            + id()
            );
        }
        state = state.paused();
    }

    public void beginKilling() {
        requireUnfinished();
        state = state.killing();
    }

    public void killUnfinishedTaskRuns() {
        requireState(State.Type.KILLING);
        if (unfinishedTaskRuns().isEmpty()) {
            return;
        }
        taskRuns.stream().filter(TaskRun::isUnfinished).forEach(TaskRun::kill);
    }

    public void finishKilling() {
        requireState(State.Type.KILLING);
        if (!unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException(
                    "Execution still has unfinished TaskRun: " + id()
            );
        }
        state = state.killed();
    }

    public TaskRun requireTaskRun(String taskRunId) {
        return findTaskRun(taskRunId).orElseThrow(() -> new WorkflowException("TaskRun does not exist: " + taskRunId));
    }

    public Execution copy() {
        return new Execution(
                id(),
                companyId(),
                creator(),
                createdAt(),
                flowKey,
                flowVersion,
                inputs,
                state,
                taskRuns
        );
    }

    private void validateRehydratedState() {
        HashSet<String> ids = new HashSet<>();
        HashSet<TaskOccurrence> occurrences = new HashSet<>();
        for (TaskRun taskRun : taskRuns) {
            if (!ids.add(taskRun.id())) {
                throw new IllegalArgumentException("Execution has duplicate TaskRun id: " + taskRun.id());
            }
            if (!occurrences.add(TaskOccurrence.from(taskRun))) {
                throw new IllegalArgumentException("Execution has duplicate TaskRun occurrence: " + taskRun.taskId());
            }
            taskRun.parentId().ifPresent(parentTaskRunId -> {
                if (!ids.contains(parentTaskRunId)) {
                    throw new IllegalArgumentException("TaskRun parent must precede child in Execution: " + taskRun.id());
                }
            });
        }
        if (state.is(State.Type.CREATED) && !taskRuns.isEmpty()) {
            throw new IllegalArgumentException("Created Execution must not have TaskRuns");
        }
        if (state.isTerminal() && !unfinishedTaskRuns().isEmpty()) {
            throw new IllegalArgumentException("Terminal Execution must not have unfinished TaskRuns");
        }
        if (state.is(State.Type.PAUSED) && (!activeTaskRuns().isEmpty() || pausedTaskRuns().isEmpty())) {
            throw new IllegalArgumentException("Paused Execution must be at a stable Pause TaskRun");
        }
        validateStateRoute();
    }

    private static final class TaskOccurrence {

        private final String taskId;
        private final String parentTaskRunId;
        private final Integer iteration;

        private TaskOccurrence(String taskId, String parentTaskRunId, Integer iteration) {
            this.taskId = taskId;
            this.parentTaskRunId = parentTaskRunId;
            this.iteration = iteration;
        }

        private static TaskOccurrence from(TaskRun taskRun) {
            return new TaskOccurrence(taskRun.taskId(), taskRun.parentId().orElse(null), taskRun.iteration().isPresent() ? taskRun.iteration().getAsInt() : null);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof TaskOccurrence other)) {
                return false;
            }
            return Objects.equals(taskId, other.taskId) && Objects.equals(parentTaskRunId, other.parentTaskRunId) && Objects.equals(iteration, other.iteration);
        }

        @Override
        public int hashCode() {
            return Objects.hash(taskId, parentTaskRunId, iteration);
        }
    }

    private void validateStateRoute() {
        List<State.History> stateHistory = state.history();
        for (int index = 1; index < stateHistory.size(); index++) {
            State.Type source = stateHistory.get(index - 1).state();
            State.Type target = stateHistory.get(index).state();
            boolean valid = switch (source) {
                case CREATED -> target == State.Type.RUNNING || target == State.Type.KILLING;
                case RUNNING ->
                        target == State.Type.PAUSED || target == State.Type.SUCCESS || target == State.Type.WARNING || target == State.Type.FAILED || target == State.Type.KILLING;
                case PAUSED -> target == State.Type.RESTARTED || target == State.Type.KILLING;
                case RESTARTED -> target == State.Type.RUNNING || target == State.Type.KILLING;
                case KILLING -> target == State.Type.KILLED;
                case SUCCESS, WARNING, FAILED, KILLED -> false;
            };
            if (!valid) {
                throw new IllegalArgumentException("Invalid Execution state transition from " + source + " to " + target);
            }
        }
    }

    private void requireRunning() {
        requireState(State.Type.RUNNING);
    }

    private void requireState(State.Type expected) {
        if (!state.is(expected)) {
            throw new WorkflowException(
                    "Execution must be " + expected + " but was " + state
                            + ": " + id()
            );
        }
    }

    private void requireUnfinished() {
        if (state.isTerminal()) {
            throw new WorkflowException(
                    "Execution must be unfinished but was " + state
                            + ": " + id()
            );
        }
        if (state.is(State.Type.KILLING)) {
            throw new WorkflowException(
                    "Execution is already KILLING: " + id()
            );
        }
    }

    private static void requireTaskRunState(TaskRun taskRun, State.Type expected) {
        if (!taskRun.state().is(expected)) {
            throw new WorkflowException("TaskRun " + taskRun.id() + " must be " + expected + " but was " + taskRun.state());
        }
    }

    private static String requireText(String value, String field) {
        return RequiredUtil.required(value, field + " must not be blank")
                .trim();
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
                requireText(key, "Execution input key"),
                immutableValue(Objects.requireNonNull(
                        value,
                        "Execution input value"
                ))
        ));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(
                    requireText(String.valueOf(key), "Execution input key"),
                    immutableValue(Objects.requireNonNull(
                            nested,
                            "Execution input value"
                    ))
            ));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> immutableValue(Objects.requireNonNull(
                            item,
                            "Execution input value"
                    )))
                    .toList();
        }
        return value;
    }

}
